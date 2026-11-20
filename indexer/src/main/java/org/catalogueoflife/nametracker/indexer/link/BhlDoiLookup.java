package org.catalogueoflife.nametracker.indexer.link;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.springframework.stereotype.Component;

/** BHL's own DOIs ({@code 10.5962/bhl.part.<n>}) name the BHL part; no network. */
@Component
public class BhlDoiLookup implements IdentifierLookup {

  public static final String NAME = "bhl-doi";
  private static final Pattern PART = Pattern.compile("^10\\.5962/bhl\\.part\\.(\\d+)$", Pattern.CASE_INSENSITIVE);

  private final LinkProperties props;

  public BhlDoiLookup(LinkProperties props) {
    this.props = props;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<String> schemes() {
    return List.of(IdScheme.BHL_PART.code());
  }

  @Override
  public IdScheme.Entity appliesTo() {
    return IdScheme.Entity.WORK;
  }

  @Override
  public boolean enabled() {
    return props.enabled(NAME);
  }

  @Override
  public int order() {
    return 10;
  }

  @Override
  public int batchSize() {
    return 500;
  }

  @Override
  public boolean applies(LinkTarget t) {
    return t.doi() != null && !t.has(IdScheme.BHL_PART.code()) && PART.matcher(t.doi()).matches();
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Map<Long, List<Identifier>> out = new java.util.HashMap<>();
    for (LinkTarget t : batch) {
      Matcher m = PART.matcher(t.doi());
      if (m.matches()) out.put(t.id(), List.of(new Identifier(IdScheme.BHL_PART.code(), m.group(1))));
    }
    return out;
  }
}
