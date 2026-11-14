package org.catalogueoflife.nametracker.finder.service;

import org.catalogueoflife.nametracker.finder.api.*;
import org.catalogueoflife.nametracker.finder.doc.JatsConverter;
import org.catalogueoflife.nametracker.finder.doc.JatsOptions;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineConfig;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineNameFinder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The endpoints of {@code finder-api.yaml}, plus {@code /v1/find/text}, {@code /v1/find/jats} and {@code /v1/openapi.yaml}.
 *
 * <p>No block may be longer than the pipeline's {@code maxChunkCodePoints} (413): the text of one block goes to gnfinder
 * in one call and the pipeline's cost grows with candidates times block length. A document whose blocks are not
 * well-formed (duplicate or missing ids, missing type or text, style spans outside their block or ending before they
 * start) is a 400, never a 500 from deep inside the finder.
 */
@RestController
@RequestMapping("/v1")
public class FindController {
  private final NameFinder finder;
  private final int maxBlockCodePoints;

  public FindController(NameFinder finder) {
    this.finder = finder;
    this.maxBlockCodePoints = finder instanceof PipelineNameFinder p ? p.config().maxChunkCodePoints()
        : PipelineConfig.defaults().maxChunkCodePoints();
  }

  @GetMapping(value = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
  public FinderInfo info() {
    return finder.info();
  }

  @PostMapping(value = "/find", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public FinderResult find(@RequestBody FindRequest request) {
    if (request == null || request.document() == null) throw new ApiErrors.BadRequest("The request needs a document", null);
    validate(request.document());
    checkBlockSizes(request.document(), "");
    return finder.find(request.document(), request.options() == null ? FinderOptions.defaults() : request.options());
  }

  /** Plain text as one PARAGRAPH block {@code b1} of the document {@code text}. */
  @PostMapping(value = "/find/text", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public FinderResult findText(@RequestBody String text, @RequestParam(required = false) String language) {
    int length = CodePoints.length(text);
    if (length > maxBlockCodePoints) {
      throw new ApiErrors.TooLarge("The text has " + length + " code points; /v1/find/text takes at most " + maxBlockCodePoints
          + ". Split it into blocks (paragraphs) of at most that size and send them as one document to /v1/find.");
    }
    String lang = language == null || language.isBlank() ? null : language;
    Document doc = new Document("text", lang, List.of(new Block("b1", BlockType.PARAGRAPH, 0, text, List.of(), List.of())), Map.of());
    return finder.find(doc, FinderOptions.defaults());
  }

  /** A JATS / TaxPub article. Taxon markup and italics are kept, they are strong evidence for the finder. */
  @PostMapping(value = "/find/jats", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
      produces = MediaType.APPLICATION_JSON_VALUE)
  public FinderResult findJats(@RequestBody byte[] xml) {
    Document doc;
    try {
      doc = new JatsConverter().convert(new ByteArrayInputStream(xml), "jats", JatsOptions.defaults()).document();
    } catch (IllegalArgumentException e) {
      throw new ApiErrors.BadRequest(e.getMessage(), e);
    }
    checkBlockSizes(doc, " of the converted article");
    return finder.find(doc, FinderOptions.defaults());
  }

  /** 400 for a document the finder cannot work on; offsets are code points of the block text. */
  private static void validate(Document doc) {
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < doc.blocks().size(); i++) {
      Block b = doc.blocks().get(i);
      if (b == null) throw bad("Block " + i + " is null");
      if (b.id() == null) throw bad("Block " + i + " has no id");
      if (!ids.add(b.id())) throw bad("Duplicate block id " + b.id() + "; block ids must be unique");
      if (b.type() == null) throw bad("Block " + b.id() + " has no type");
      if (b.text() == null) throw bad("Block " + b.id() + " has no text");
      int length = CodePoints.length(b.text());
      for (StyleSpan s : b.styles()) {
        if (s == null || s.style() == null) throw bad("Block " + b.id() + " has a style span without a style");
        if (s.start() > s.end()) {
          throw bad("Block " + b.id() + " has a style span whose start " + s.start() + " is after its end " + s.end());
        }
        if (s.start() < 0 || s.end() > length) {
          throw bad("Block " + b.id() + " has a style span [" + s.start() + ", " + s.end() + ") outside its text of " + length
              + " code points");
        }
      }
    }
  }

  private static ApiErrors.BadRequest bad(String message) {
    return new ApiErrors.BadRequest(message, null);
  }

  /** 413 for a block longer than one gnfinder chunk. */
  private void checkBlockSizes(Document doc, String where) {
    for (Block b : doc.blocks()) {
      int length = CodePoints.length(b.text());
      if (length > maxBlockCodePoints) {
        throw new ApiErrors.TooLarge("Block " + b.id() + where + " has " + length + " code points; a block may have at most "
            + maxBlockCodePoints + ". Split it into several blocks and send them via /v1/find.");
      }
    }
  }

  /** The contract, so that clients can fetch it. */
  @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
  public ResponseEntity<Resource> openapi() {
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/yaml")).body(new ClassPathResource("finder-api.yaml"));
  }
}
