package org.catalogueoflife.garrulus.indexer.content.route;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.content.ContentRoute;
import org.catalogueoflife.garrulus.indexer.content.FetchedContent;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.layer.oai.OaiLayer;
import org.catalogueoflife.garrulus.indexer.layer.oai.OaiPmhClient;
import org.catalogueoflife.garrulus.indexer.layer.oai.OaiRecord;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.catalogueoflife.garrulus.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.work.Layer;
import org.catalogueoflife.garrulus.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Route {@code OAI_JATS}: the JATS record of a work from its journal's own OAI-PMH endpoint, for a source with an
 * ACTIVE or VERIFIED {@code OAI} route whose config {@code prefix} is {@code jats} (OJS journals with the JATS plugin).
 * One {@code GetRecord} per work, on the OAI channel of the host (2 s). It is full text only when the record has a
 * {@code <body>} with paragraphs; the licence is the record's own {@code <license xlink:href>}, so the text is kept only
 * under an open licence ({@code LicencePolicy}). It runs first: the journal's own copy beats the aggregators. Off with
 * the OAI layer ({@code garrulus.layers.oai.enabled=false}, the way to silence a misbehaving repository) and for a
 * journal that has ceased.
 */
@Component
@Order(0)
public class OaiJatsRoute implements ContentRoute {

  public static final String ROUTE = "OAI_JATS";
  static final String PREFIX = "jats";
  private static final Set<String> USABLE = Set.of("ACTIVE", "VERIFIED");

  private final OaiPmhClient client;
  private final OaiLayer layer;
  private final SourceRouteMapper routes;
  private final WorkIdentifierMapper identifiers;
  private final Environment env;

  @Autowired
  public OaiJatsRoute(PoliteHttp http, OaiLayer layer, SourceRouteMapper routes, WorkIdentifierMapper identifiers,
      Environment env) {
    this.client = new OaiPmhClient(http);
    this.layer = layer;
    this.routes = routes;
    this.identifiers = identifiers;
    this.env = env;
  }

  @Override
  public String route() {
    return ROUTE;
  }

  /** Where to ask: the endpoint of the journal's JATS route and the work's OAI identifier. */
  private record Target(URI base, String identifier) {
  }

  private @Nullable Target target(Work w, @Nullable Source s) {
    if (w.id() == null || s == null || s.id() == null || "CEASED".equals(s.publicationStatus())) return null;
    if (!env.getProperty(Layer.OAI.enabledProperty(), Boolean.class, true)) return null;
    String oai = identifiers.list(w.id()).stream().filter(i -> IdScheme.OAI.code().equals(i.scheme()))
        .map(Identifier::value).findFirst().orElse(null);
    if (oai == null) return null;
    for (SourceRoute r : routes.forSource(s.id())) {
      if (!"OAI".equals(r.layer()) || !USABLE.contains(r.status())) continue;
      JsonNode cfg;
      try {
        cfg = FinderJson.mapper().readTree(r.config());
      } catch (RuntimeException e) {
        continue;
      }
      String base = cfg.path("base").asString("").strip();
      if (!PREFIX.equals(cfg.path("prefix").asString("")) || base.isEmpty()) continue;
      try {
        return new Target(URI.create(base), oai);
      } catch (IllegalArgumentException e) {
        // an unusable base: the next route
      }
    }
    return null;
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return target(w, s) != null;
  }

  @Override
  public Optional<FetchedContent> fetch(Work w, @Nullable Source s) {
    Target t = target(w, s);
    if (t == null) return Optional.empty();
    layer.polite(t.base());
    Optional<OaiRecord> rec = client.getRecord(t.base(), t.identifier(), PREFIX);
    if (rec.isEmpty() || rec.get().metadata() == null) return Optional.empty();
    byte[] xml = serialize(rec.get().metadata());
    URI uri = getRecordUri(t);
    return JatsSupport.parse(xml, "work:" + w.id(), uri)
        .map(p -> JatsSupport.content(ROUTE, "JATS", xml, p, p.license(), uri));
  }

  private static URI getRecordUri(Target t) {
    String b = t.base().toString();
    return URI.create(b + (t.base().getRawQuery() == null ? '?' : '&') + "verb=GetRecord&identifier="
        + URLEncoder.encode(t.identifier(), StandardCharsets.UTF_8) + "&metadataPrefix=" + PREFIX);
  }

  /** The record's metadata element as XML bytes (UTF-8), namespaces kept. */
  static byte[] serialize(org.w3c.dom.Element metadata) {
    try {
      TransformerFactory f = TransformerFactory.newInstance();
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      Transformer tr = f.newTransformer();
      tr.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      tr.transform(new DOMSource(metadata), new StreamResult(out));
      return out.toByteArray();
    } catch (TransformerException e) {
      throw new IllegalStateException("Cannot serialise the OAI record", e);
    }
  }
}
