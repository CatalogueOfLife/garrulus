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
 * <p>Dropped: candidates the parser cannot parse, and parsed ones whose name type is not SCIENTIFIC (informal,
 * placeholder, OTU, formula ...). TAXON_MARKUP candidates the parser cannot parse are kept as UNINOMIAL / UNRANKED.
 *
 * <p>Rank: the parsed rank for binomials and below; for uninomials the dictionary rank, else the suffix rank, else GENUS
 * for a dictionary genus, else UNRANKED. The code is the hint if given, else the parser's (possibly null).
 */
public class ParserStage {
  private static final Pattern ABBREVIATED = Pattern.compile("^\\p{Lu}\\p{Ll}{0,2}\\.(\\s.*)?$", Pattern.DOTALL);
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
    ParseResult result = run(c, name, codeHint);
    Optional<ParsedName> parsed = result == null ? Optional.empty() : result.parsed();
    if (parsed.isEmpty()) {
      if (abbreviated) return Optional.of(new ParsedCandidate(c, name, NameKind.ABBREVIATED, Rank.UNRANKED, codeHint));
      if (c.generators().contains(Generator.TAXON_MARKUP)) {
        return Optional.of(new ParsedCandidate(c, name, NameKind.UNINOMIAL, Rank.UNRANKED, codeHint));
      }
      return Optional.empty();
    }
    ParsedName pn = parsed.get();
    // the parser types an abbreviated genus ("C. nigrus") as INFORMAL, which is no reason to drop a valid abbreviated mention
    NameType type = pn.getType() != null ? pn.getType() : result.type();
    if (!abbreviated && type != null && type != NameType.SCIENTIFIC) return Optional.empty();
    NomCode code = codeHint != null ? codeHint : pn.getCode() != null ? pn.getCode() : result.code();
    String canonical = pn.canonicalNameWithoutAuthorship();
    if (canonical == null || canonical.isBlank()) canonical = name;
    if (abbreviated) return Optional.of(new ParsedCandidate(c, canonical, NameKind.ABBREVIATED, pn.getRank(), code));
    NameKind kind;
    Rank rank = pn.getRank();
    if (pn.getInfraspecificEpithet() != null) {
      kind = NameKind.TRINOMIAL;
    } else if (pn.getSpecificEpithet() != null) {
      kind = NameKind.BINOMIAL;
    } else if (pn.getInfragenericEpithet() != null) {
      kind = NameKind.INFRAGENERIC;
    } else {
      kind = NameKind.UNINOMIAL;
      String word = pn.getUninomial() != null ? pn.getUninomial() : canonical;
      rank = uninomialRank(word, code);
    }
    return Optional.of(new ParsedCandidate(c, canonical, kind, rank, code));
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
