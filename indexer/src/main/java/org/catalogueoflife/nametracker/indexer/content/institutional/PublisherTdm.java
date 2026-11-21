package org.catalogueoflife.nametracker.indexer.content.institutional;

import java.net.URI;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.Document;

/**
 * One publisher's text-and-data-mining API for subscribers. The {@link InstitutionalRoute} makes the request (through
 * PoliteHttp: the host's own limiter, the token headers only on the API's own authority), audits it and caps it; a
 * publisher only says which DOIs it serves, where and how to ask, and how to read the answer.
 */
public interface PublisherTdm {

  /** The allow-list and audit name, upper case ({@code ELSEVIER}). */
  String publisher();

  /** The audited API ({@code institutional_fetch.api}). */
  String api();

  /** The {@code content.kind} of what {@link #convert} reads. */
  String kind();

  /** True when the DOI (lower case) is one of this publisher's. */
  boolean claims(String doi);

  /** The request URL of a DOI; it carries no secret. */
  URI url(String doi);

  /** The request headers, tokens included. */
  Map<String, String> headers();

  /**
   * Reads a 200 answer into a document. An unusable answer throws {@link IllegalArgumentException}; a local service it
   * needs that is down throws a retryable {@link org.catalogueoflife.nametracker.indexer.http.HttpProblem}.
   */
  Document convert(byte[] body, String docId);
}
