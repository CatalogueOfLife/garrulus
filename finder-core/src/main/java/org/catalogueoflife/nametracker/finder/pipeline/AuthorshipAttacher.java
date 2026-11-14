package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.gbif.nameparser.api.NameParser;
import org.gbif.nameparser.api.ParsedName;
import org.gbif.nameparser.api.Rank;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attaches an authorship to a merged candidate and splits off qualifiers.
 *
 * <p>Authorship: the up to 80 code points after the span are matched against author patterns, longest first, and a
 * pattern is accepted only if the name parser, given {@code name + " " + authorship}, returns exactly that
 * authorship (compared after dropping whitespace) for the same canonical name. The span is never extended; the
 * authorship is a separate field. A bare surname without a year or an abbreviation dot is deliberately not accepted
 * ("Rosa canina Adults were ..." must not yield the author "Adults").
 *
 * <p>Qualifiers: cf., aff., nr., sp., spp., s.l., s.str. found in the span are removed from the name ("Carabus cf.
 * nigrus" becomes "Carabus nigrus" with qualifier "cf."); the verbatim text and the span are untouched. A "sp." or
 * "spp." directly after a lone genus, even when just outside the span, is recorded as a qualifier as well. "sp. nov."
 * and "sp. n." are nomenclatural act markers and never a qualifier.
 *
 * <p>Deliberately unsupported: "in" citations ("Smith in Jones, 1900"), a combined basionym-plus-year form such as
 * "(Fabricius, 1775) Smith, 1900", a qualifier before the genus, and more than one qualifier (only the first is taken).
 */
public class AuthorshipAttacher {
  static final int LOOKAHEAD = 80;
  private static final Set<String> QUALIFIERS = Set.of("cf.", "aff.", "nr.", "sp.", "spp.", "s.l.", "s.str.");
  private static final Set<String> NOV = Set.of("nov.", "nov", "n.", "nova", "novum", "n.sp.", "sp.nov.");

  private static final String LOWER_PARTICLE = "(?:(?:de|van|von|der|den|di|da|du|le|la|del|zu|ter)\\s)*";
  private static final String SURNAME = LOWER_PARTICLE + "\\p{Lu}[\\p{L}'’\\-]+";
  // an abbreviated author ending in a dot: "L.", "Benth.", "Hook.f.", "A.Gray"
  private static final String ABBR = "(?:\\p{Lu}\\.\\s?){0,3}" + LOWER_PARTICLE + "\\p{Lu}[\\p{L}'’\\-]*\\.(?:f\\.|fil\\.)?";
  private static final String ANY = "(?:(?:\\p{Lu}\\.\\s?){0,3}" + SURNAME + "\\.?(?:f\\.)?|\\p{Lu}\\.)";
  private static final String SEP = "(?:\\s*&\\s*|\\s+et\\s+|\\s+ex\\s+|,\\s*)";
  private static final String YEAR = "\\d{4}[a-z]?";

  private static final List<Pattern> PATTERNS = List.of(
      // (Linnaeus, 1758) / (Smith & Jones, 1999)
      Pattern.compile("\\(" + ANY + "(?:" + SEP + ANY + ")*,?\\s*" + YEAR + "\\)"),
      // (L.) Benth.
      Pattern.compile("\\(" + ABBR + "(?:" + SEP + ABBR + ")*\\)\\s*" + ABBR + "(?:" + SEP + ABBR + ")*"),
      // Savigny, 1822 / Smith & Jones, 1999
      Pattern.compile(ANY + "(?:" + SEP + ANY + ")*,?\\s*" + YEAR),
      // L. / Benth. / Hook.f. / L. & Benth.
      Pattern.compile(ABBR + "(?:" + SEP + ABBR + ")*"),
      // A. Smith
      Pattern.compile("(?:\\p{Lu}\\.\\s?){1,3}" + SURNAME));

  private static final Pattern TAIL_QUALIFIER = Pattern.compile(
      "^\\s+(sp\\.|spp\\.|s\\.l\\.|s\\.str\\.)(?=$|[\\s,;:)\\]])(\\s+(\\S+))?", Pattern.CASE_INSENSITIVE);
  private static final Pattern LEADING_WS = Pattern.compile("^\\s+");
  /** Abbreviations of the form "Fig." that the parser may take for an author but never are. */
  private static final Set<String> NOT_AUTHORS = Set.of("fig.", "figs.", "tab.", "pl.", "pp.", "p.", "no.", "vol.");
  private static final System.Logger LOG = System.getLogger(AuthorshipAttacher.class.getName());

  private final NameParser parser;

  public AuthorshipAttacher(NameParser parser) {
    this.parser = parser;
  }

  public MergedCandidate attach(MergedCandidate c, Block block) {
    MergedCandidate q = qualify(c, block);
    // a qualifier found after the span ("Carabus sp.") sits where an authorship would be
    boolean tailQualifier = q.qualifier() != null && c.qualifier() == null && q.name().equals(c.name())
        && java.util.Arrays.stream(c.verbatim().split("\\s+")).noneMatch(t -> QUALIFIERS.contains(t.toLowerCase()));
    return tailQualifier ? q : withAuthorship(q, block);
  }

  // ---- qualifiers

  private MergedCandidate qualify(MergedCandidate c, Block block) {
    String[] tokens = c.name().split("\\s+");
    String[] vtokens = c.verbatim().split("\\s+");
    String found = null;
    int idx = -1;
    for (int i = 1; i < vtokens.length; i++) {
      String t = vtokens[i].toLowerCase();
      if (QUALIFIERS.contains(t) && !isNov(vtokens, i)) {
        found = t;
        idx = i;
        break;
      }
    }
    if (found != null) {
      List<String> keep = new ArrayList<>();
      for (String t : tokens) {
        if (!t.equalsIgnoreCase(found)) keep.add(t);
      }
      return c.withQualifier(String.join(" ", keep), found);
    }
    // outside the span: "sp." / "spp." after a lone genus, "s.l." / "s.str." after any name
    String tail = tail(c, block);
    Matcher m = TAIL_QUALIFIER.matcher(tail);
    if (m.find()) {
      String qual = m.group(1).toLowerCase();
      boolean sp = qual.startsWith("sp");
      if ((!sp || tokens.length == 1) && (m.group(3) == null || !NOV.contains(m.group(3).toLowerCase()))) {
        return c.withQualifier(c.name(), qual);
      }
    }
    return c;
  }

  private static boolean isNov(String[] tokens, int i) {
    if (!tokens[i].toLowerCase().startsWith("sp")) return false;
    return i + 1 < tokens.length && NOV.contains(tokens[i + 1].toLowerCase());
  }

  // ---- authorship

  private MergedCandidate withAuthorship(MergedCandidate c, Block block) {
    String tail = tail(c, block);
    Matcher ws = LEADING_WS.matcher(tail);
    if (!ws.find()) return c;
    String rest = tail.substring(ws.end());
    Set<String> options = new LinkedHashSet<>();
    List<String> matches = new ArrayList<>();
    for (Pattern p : PATTERNS) {
      Matcher m = p.matcher(rest);
      if (m.lookingAt()) matches.add(m.group().strip());
    }
    matches.sort((a, b) -> b.length() - a.length());
    options.addAll(matches);
    String name = c.name();
    Optional<ParsedName> bare = parse(name, null);
    if (bare.isEmpty() || bare.get().hasAuthorship()) return c;
    String canonical = bare.get().canonicalNameWithoutAuthorship();
    for (String auth : options) {
      if (NOT_AUTHORS.contains(auth.toLowerCase())) continue;
      var parsed = parse(name, auth);
      if (parsed.isPresent() && canonical.equals(parsed.get().canonicalNameWithoutAuthorship())
          && parsed.get().hasAuthorship()
          && squash(parsed.get().authorshipComplete()).equals(squash(auth))) {
        return c.withAuthorship(auth);
      }
    }
    return c;
  }

  private Optional<ParsedName> parse(String name, String authorship) {
    try {
      return parser.parse(authorship == null ? name : name + " " + authorship, null, Rank.UNRANKED, null).parsed();
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.DEBUG, "name parser failed on '" + name + "'", e);
      return Optional.empty();
    }
  }

  private static String squash(String s) {
    return s.replaceAll("\\s+", "");
  }

  private static String tail(MergedCandidate c, Block block) {
    int len = CodePoints.length(block.text());
    if (c.end() >= len) return "";
    return CodePoints.slice(block.text(), c.end(), Math.min(len, c.end() + LOOKAHEAD));
  }
}
