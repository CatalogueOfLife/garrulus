package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.doc.FlatText;

import java.util.List;

/** Proposes name candidates for a document. Dependencies such as clients or dictionaries come through the constructor. */
public interface CandidateGenerator {
  Generator id();

  List<Candidate> generate(Document doc, FlatText flat, GenerationContext ctx);
}
