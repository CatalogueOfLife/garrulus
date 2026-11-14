package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.gbif.nameparser.api.NameParser;
import org.gbif.nameparser.api.NameType;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.ParseResult;
import org.gbif.nameparser.api.ParsedName;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Runs the name parser over a merged candidate (after abbreviation expansion) to fix the canonical form, kind, rank and
 * code, and drops what is not a scientific name.
 *
 * <p>The authorship is passed to the parser but the canonical form never contains it. Kind: uninomial, binomial (species
 * epithet, with or without a subgenus: "Carabus (Megodontus) nigrus" is a BINOMIAL), trinomial (infraspecific epithet) or
 * INFRAGENERIC (infrageneric epithet but no species epithet). A name that still starts with an abbreviated genus
 * ("C. nigrus", not expanded or ambiguous) is ABBREVIATED; it is fed to the parser as is and, should the parser fail on
 * it, kept as ABBREVIATED anyway because abbreviations are valid mentions.
 *
 * <p>An abbreviation needs an initial or short abbreviation followed by a lower case epithet ("C. nigrus", "Pt. niger",
 * "L. g. confertiflora"); "Fig. 3", "Sp. nov." or "St. Louis" are not. Only the parser's INFORMAL typing of such a genuine
 * abbreviation is exempt from the type filter.
 *
 * <p>Dropped: candidates the parser cannot parse, and parsed ones whose name type is not SCIENTIFIC (informal,
 * placeholder, OTU, formula ...). TAXON_MARKUP (publisher-tagged) candidates are never dropped: unparseable ones are kept
 * as UNINOMIAL / UNRANKED, non-scientific ones with whatever the parser gave.
 *
 * <p>Rank: the parsed rank for binomials and below; for uninomials the dictionary rank, else the suffix rank, else GENUS
 * for a dictionary genus, else UNRANKED. The code is the hint if given, else the parser's (possibly null).
 */
public class ParserStage {
  private static final Pattern ABBREVIATED = Pattern.compile(
      "^\\p{Lu}\\p{Ll}{0,2}\\.\\s+(?:\\p{Ll}{1,3}\\.\\s+)?\\p{Ll}[\\p{Ll}-]+(?:\\s.*)?$", Pattern.DOTALL);
  private static final Pattern LEADING_DOT_WORD = Pattern.compile("^\\p{Lu}\\p{Ll}{0,2}\\.");
  private static final System.Logger LOG = System.getLogger(ParserStage.class.getName());

  private final NameParser parser;
  private final ColDictionary dict;

  public ParserStage(NameParser parser, ColDictionary dict) {
    this.parser = parser;
    this.dict = dict;
  }

  public Optional<ParsedCandidate> parse(MergedCandidate c, @Nullable NomCode codeHint) {
    String name = c.name().strip();
    boolean abbreviated = ABBREVIATED.matcher(name).matches();
    boolean markup = c.generators().contains(Generator.TAXON_MARKUP);
    // a leading "Xx." that is not a genuine abbreviation ("Fig. 3", "St. Louis") is no name, whatever the parser makes of it
    if (!abbreviated && !markup && LEADING_DOT_WORD.matcher(name).find()) return Optional.empty();
    String bare = withoutAuthorship(name, c.authorship());
    ParseResult result = run(c, name, codeHint);
    Optional<ParsedName> parsed = result == null ? Optional.empty() : result.parsed();
    if (parsed.isEmpty()) {
      // an abbreviation the parser cannot handle is still a valid mention; publisher-tagged names are never dropped
      if (abbreviated) return Optional.of(new ParsedCandidate(c, bare, NameKind.ABBREVIATED, Rank.UNRANKED, codeHint));
      if (markup) return Optional.of(new ParsedCandidate(c, bare, NameKind.UNINOMIAL, Rank.UNRANKED, codeHint));
      return Optional.empty();
    }
    ParsedName pn = parsed.get();
    NameType type = pn.getType() != null ? pn.getType() : result.type();
    boolean scientific = type == null || type == NameType.SCIENTIFIC;
    // the parser types an abbreviated genus ("C. nigrus") as INFORMAL; only that case is exempt from the type filter
    boolean genuineAbbreviation = abbreviated && type == NameType.INFORMAL && pn.isAbbreviated();
    if (!scientific && !genuineAbbreviation && !markup) return Optional.empty();
    NomCode code = codeHint != null ? codeHint : pn.getCode() != null ? pn.getCode() : result.code();
    String canonical = pn.canonicalNameWithoutAuthorship();
    if (canonical == null || canonical.isBlank()) canonical = bare;
    Rank rank = pn.getRank() != null ? pn.getRank() : Rank.UNRANKED;
    if (abbreviated && (scientific || genuineAbbreviation || markup)) {
      return Optional.of(new ParsedCandidate(c, canonical, NameKind.ABBREVIATED, rank, code));
    }
    NameKind kind;
    if (pn.getInfraspecificEpithet() != null) {
      kind = NameKind.TRINOMIAL;
    } else if (pn.getSpecificEpithet() != null) {
      kind = NameKind.BINOMIAL;
    } else if (pn.getInfragenericEpithet() != null) {
      kind = NameKind.INFRAGENERIC;
    } else {
      kind = NameKind.UNINOMIAL;
      if (scientific) {
        String word = pn.getUninomial() != null ? pn.getUninomial() : canonical;
        rank = uninomialRank(word, code);
      }
    }
    return Optional.of(new ParsedCandidate(c, canonical, kind, rank, code));
  }

  private static String withoutAuthorship(String name, @Nullable String authorship) {
    if (authorship != null && !authorship.isBlank() && name.endsWith(authorship.strip())) {
      return name.substring(0, name.length() - authorship.strip().length()).strip();
    }
    return name;
  }

  private Rank uninomialRank(String word, @Nullable NomCode code) {
    return dict.uninomialRank(word)
        .or(() -> SuffixRules.rankFor(word, code))
        .orElse(dict.isGenus(word) ? Rank.GENUS : Rank.UNRANKED);
  }

  private ParseResult run(MergedCandidate c, String name, @Nullable NomCode codeHint) {
    try {
      return parser.parse(name, c.authorship(), null, codeHint);
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.DEBUG, "name parser failed on '" + name + "'", e);
      return null;
    }
  }
}
