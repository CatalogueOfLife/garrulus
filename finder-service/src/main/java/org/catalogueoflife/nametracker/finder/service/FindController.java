package org.catalogueoflife.nametracker.finder.service;

import org.catalogueoflife.nametracker.finder.api.*;
import org.catalogueoflife.nametracker.finder.doc.JatsConverter;
import org.catalogueoflife.nametracker.finder.doc.JatsOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

/** The endpoints of {@code finder-api.yaml}, plus {@code /v1/find/text}, {@code /v1/find/jats} and {@code /v1/openapi.yaml}. */
@RestController
@RequestMapping("/v1")
public class FindController {
  private final NameFinder finder;

  public FindController(NameFinder finder) {
    this.finder = finder;
  }

  @GetMapping(value = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
  public FinderInfo info() {
    return finder.info();
  }

  @PostMapping(value = "/find", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public FinderResult find(@RequestBody FindRequest request) {
    if (request == null || request.document() == null) throw new ApiErrors.BadRequest("The request needs a document", null);
    return finder.find(request.document(), request.options() == null ? FinderOptions.defaults() : request.options());
  }

  /** Plain text as one PARAGRAPH block {@code b1} of the document {@code text}. */
  @PostMapping(value = "/find/text", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public FinderResult findText(@RequestBody String text, @RequestParam(required = false) String language) {
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
    return finder.find(doc, FinderOptions.defaults());
  }

  /** The contract, so that clients can fetch it. */
  @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
  public ResponseEntity<Resource> openapi() {
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/yaml")).body(new ClassPathResource("finder-api.yaml"));
  }
}
