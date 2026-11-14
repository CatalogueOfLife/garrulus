package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.pipeline.ActPatterns.ActMatch;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ActTaggerTest {
  private static Block block(String id, BlockType t, String text) {
    return new Block(id, t, 0, text, List.of(), List.of());
  }

  private static Document doc(Block... bs) {
    return new Document("d", "en", List.of(bs), Map.of());
  }

  /** Candidate for the first occurrence of name at or after from in the block text. */
  private static ParsedCandidate pc(Block b, String name, NameKind kind, Rank rank) {
    int s = b.text().indexOf(name);
    return new ParsedCandidate(new MergedCandidate(b.id(), s, s + name.length(), name, name, Set.of(Generator.GNFINDER),
        Map.of(), null, null), name, kind, rank, null);
  }

  private final ActTagger tagger = new ActTagger();

  @Test
  void spNovAttachesToSpeciesNotFamilyChain() {
    Block b = block("b1", BlockType.PARAGRAPH,
        "A new beetle Carabus (Coleoptera: Carabidae), Carabus nigrus sp. nov., is described");
    ParsedCandidate carabus = pc(b, "Carabus", NameKind.UNINOMIAL, Rank.GENUS);
    ParsedCandidate coleoptera = pc(b, "Coleoptera", NameKind.UNINOMIAL, Rank.ORDER);
    ParsedCandidate carabidae = pc(b, "Carabidae", NameKind.UNINOMIAL, Rank.FAMILY);
    ParsedCandidate nigrus = pc(b, "Carabus nigrus", NameKind.BINOMIAL, Rank.SPECIES);
    Map<ParsedCandidate, ActMatch> r = tagger.tag(List.of(carabus, coleoptera, carabidae, nigrus), doc(b));
    assertThat(r).containsOnlyKeys(nigrus);
    assertThat(r.get(nigrus).type()).isEqualTo(ActType.SP_NOV);
  }

  @Test
  void combNovOnBinomial() {
    Block b = block("b1", BlockType.PARAGRAPH, "Bar baz (Smith, 1900) comb. nov. is moved");
    ParsedCandidate p = pc(b, "Bar baz", NameKind.BINOMIAL, Rank.SPECIES);
    assertThat(tagger.tag(List.of(p), doc(b)).get(p).type()).isEqualTo(ActType.COMB_NOV);
  }

  @Test
  void spanishNuevaEspecie() {
    Block b = block("b1", BlockType.PARAGRAPH, "Describimos Bar baz, nueva especie de Chile");
    ParsedCandidate p = pc(b, "Bar baz", NameKind.BINOMIAL, Rank.SPECIES);
    assertThat(tagger.tag(List.of(p), doc(b)).get(p).type()).isEqualTo(ActType.SP_NOV);
  }

  @Test
  void genNovNeedsGenusRank() {
    Block b = block("b1", BlockType.PARAGRAPH, "Carabidae Foogenus gen. nov. is erected");
    ParsedCandidate fam = pc(b, "Carabidae", NameKind.UNINOMIAL, Rank.FAMILY);
    ParsedCandidate gen = pc(b, "Foogenus", NameKind.UNINOMIAL, Rank.GENUS);
    assertThat(tagger.tag(List.of(fam, gen), doc(b))).containsOnlyKeys(gen);
    Block b2 = block("b2", BlockType.PARAGRAPH, "Carabidae gen. nov.");
    ParsedCandidate fam2 = pc(b2, "Carabidae", NameKind.UNINOMIAL, Rank.FAMILY);
    ActTagger.Result r = tagger.tagWithDiagnostics(List.of(fam2), doc(b2));
    assertThat(r.acts()).isEmpty();
    assertThat(r.actsUnattached()).isEqualTo(1);
  }

  @Test
  void lectotypeDesignation() {
    Block b = block("b1", BlockType.PARAGRAPH, "Bar baz Smith, 1900: lectotype designated here (ZMUC 1)");
    ParsedCandidate p = pc(b, "Bar baz", NameKind.BINOMIAL, Rank.SPECIES);
    assertThat(tagger.tag(List.of(p), doc(b)).get(p).type()).isEqualTo(ActType.TYPE_DESIGNATION);
  }

  @Test
  void markerTooFarIsUnattached() {
    Block b = block("b1", BlockType.PARAGRAPH, "Bar baz was found in a remote valley of the high Andes long ago, sp. nov.");
    ParsedCandidate p = pc(b, "Bar baz", NameKind.BINOMIAL, Rank.SPECIES);
    ActTagger.Result r = tagger.tagWithDiagnostics(List.of(p), doc(b));
    assertThat(r.acts()).isEmpty();
    assertThat(r.actsUnattached()).isEqualTo(1);
    assertThat(new ActTagger(100).tag(List.of(p), doc(b))).containsOnlyKeys(p);
  }

  @Test
  void headingMarkerAttachesToPrecedingHeading() {
    Block h1 = block("h1", BlockType.HEADING, "Foo bar Smith, 2020");
    Block h2 = block("h2", BlockType.HEADING, "sp. nov.");
    ParsedCandidate p = pc(h1, "Foo bar", NameKind.BINOMIAL, Rank.SPECIES);
    assertThat(tagger.tag(List.of(p), doc(h1, h2)).get(p).type()).isEqualTo(ActType.SP_NOV);
    // not a whole marker: no cross block attachment
    Block h3 = block("h3", BlockType.HEADING, "Description of sp. nov.");
    assertThat(tagger.tag(List.of(p), doc(h1, h3))).isEmpty();
  }

  @Test
  void wholeHeadingCompoundMarkerAttachesToPrecedingHeading() {
    Block h1 = block("h1", BlockType.HEADING, "Foo bar Smith, 2020");
    Block h2 = block("h2", BlockType.HEADING, "gen. et sp. nov.");
    ParsedCandidate p = pc(h1, "Foo bar", NameKind.BINOMIAL, Rank.SPECIES);
    assertThat(tagger.tag(List.of(p), doc(h1, h2)).get(p).type()).isEqualTo(ActType.SP_NOV);
  }

  @Test
  void nearestFittingNameTakenMeansUnattachedNoFallback() {
    Block b = block("b1", BlockType.PARAGRAPH, "Aus bus Smith, 1900 and Cus dus comb. nov., stat. nov.");
    ParsedCandidate aus = pc(b, "Aus bus", NameKind.BINOMIAL, Rank.SPECIES);
    ParsedCandidate cus = pc(b, "Cus dus", NameKind.BINOMIAL, Rank.SPECIES);
    ActTagger.Result r = tagger.tagWithDiagnostics(List.of(aus, cus), doc(b));
    assertThat(r.acts()).containsOnlyKeys(cus);
    assertThat(r.acts().get(cus).type()).isEqualTo(ActType.COMB_NOV);
    assertThat(r.actsUnattached()).isEqualTo(1);
  }

  @Test
  void compoundInRunningTextTagsLikeClassify() {
    Block b = block("b1", BlockType.PARAGRAPH, "Aus bus comb. et stat. nov. is proposed");
    ParsedCandidate p = pc(b, "Aus bus", NameKind.BINOMIAL, Rank.SPECIES);
    ActMatch m = tagger.tag(List.of(p), doc(b)).get(p);
    assertThat(m.type()).isEqualTo(ActType.COMB_NOV);
    assertThat(m.type()).isEqualTo(ActPatterns.classify("comb. et stat. nov.").orElseThrow());
  }

  @Test
  void distanceFortyAttachesFortyOneDoesNot() {
    // the gap holds one supplementary code point (2 chars) so that code points and chars differ
    String gap40 = " ".repeat(38) + "\ud83d\udc1b" + " ";
    Block b40 = block("b1", BlockType.PARAGRAPH, "Aus bus" + gap40 + "sp. nov.");
    ParsedCandidate p40 = pc(b40, "Aus bus", NameKind.BINOMIAL, Rank.SPECIES);
    assertThat(tagger.tag(List.of(p40), doc(b40))).containsOnlyKeys(p40);
    Block b41 = block("b2", BlockType.PARAGRAPH, "Aus bus" + gap40 + " sp. nov.");
    ParsedCandidate p41 = pc(b41, "Aus bus", NameKind.BINOMIAL, Rank.SPECIES);
    assertThat(tagger.tag(List.of(p41), doc(b41))).isEmpty();
  }

  @Test
  void sspAndVarNovNeedTrinomialFamNovNeedsFamilyGroup() {
    Block b = block("b1", BlockType.PARAGRAPH, "Aus bus cus subsp. nov. and Dus eus var. nov.");
    ParsedCandidate tri = pc(b, "Aus bus cus", NameKind.TRINOMIAL, Rank.SUBSPECIES);
    ParsedCandidate bin = pc(b, "Dus eus", NameKind.BINOMIAL, Rank.SPECIES);
    assertThat(tagger.tag(List.of(tri, bin), doc(b))).containsOnlyKeys(tri);

    Block f = block("b2", BlockType.PARAGRAPH, "Carabidae fam. nov. and Foogenus fam. nov.");
    ParsedCandidate fam = pc(f, "Carabidae", NameKind.UNINOMIAL, Rank.FAMILY);
    ParsedCandidate gen = pc(f, "Foogenus", NameKind.UNINOMIAL, Rank.GENUS);
    ActTagger.Result r = tagger.tagWithDiagnostics(List.of(fam, gen), doc(f));
    assertThat(r.acts()).containsOnlyKeys(fam);
    assertThat(r.actsUnattached()).isEqualTo(1);
  }
}
