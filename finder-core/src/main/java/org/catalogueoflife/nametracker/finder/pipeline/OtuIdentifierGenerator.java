package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.doc.FlatText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the identifiers of dark taxa, OTUs: BOLD BINs ("BOLD:AAB2345", also "BOLD: AAB2345") and UNITE species
 * hypotheses ("SH1556109.08FU", also inside a {@code 10.15156/BIO/SH...} DOI, where the span is the SH code only).
 * A BIN's span may cross one whitespace character after the colon: a space, a no-break space or a newline. Case-sensitive and bounded by word boundaries, so nothing matches inside a longer token. The name is the normalised
 * identifier (built from the letters and digits, so no whitespace of any kind in a BIN) and feature {@code scheme} is {@value #BOLD_BIN} or {@value #UNITE_SH}. The pipeline
 * treats these candidates as certain and never parses or judges them.
 */
public class OtuIdentifierGenerator implements CandidateGenerator {
  public static final String SCHEME = "scheme";
  public static final String BOLD_BIN = "BOLD_BIN";
  public static final String UNITE_SH = "UNITE_SH";

  private static final Pattern BIN = Pattern.compile("\\bBOLD:\\s?([A-Z]{3})([0-9]{4})\\b", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern SH = Pattern.compile("\\bSH[0-9]{6,7}\\.[0-9]{2}FU\\b", Pattern.UNICODE_CHARACTER_CLASS);

  @Override
  public Generator id() {
    return Generator.OTU;
  }

  @Override
  public List<Candidate> generate(Document doc, FlatText flat, GenerationContext ctx) {
    List<Candidate> out = new ArrayList<>();
    for (Block b : doc.blocks()) {
      String t = b.text();
      if (t.length() < 12) continue;
      add(out, b, t, BIN, BOLD_BIN);
      add(out, b, t, SH, UNITE_SH);
    }
    return out;
  }

  private static void add(List<Candidate> out, Block b, String t, Pattern p, String scheme) {
    Matcher m = p.matcher(t);
    while (m.find()) {
      String verbatim = m.group();
      String name = BOLD_BIN.equals(scheme) ? "BOLD:" + m.group(1) + m.group(2) : verbatim;
      out.add(new Candidate(b.id(), t.codePointCount(0, m.start()), t.codePointCount(0, m.end()), verbatim, name, Generator.OTU,
          Map.of(SCHEME, scheme)));
    }
  }
}
