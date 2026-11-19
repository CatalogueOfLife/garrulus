package org.catalogueoflife.nametracker.store.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkMergerTest {

  private static Work work(String title, String license, String metaLayer, String accessLayer, String state) {
    return new Work(1L, null, null, null, title, null, null, null, null, null, null, null, null, null, license,
        null, null, null, "HARVEST", state, "NONE", null, null, null, Instant.EPOCH, null, null, null, null, null,
        metaLayer, null, accessLayer);
  }

  private static Work work(String title, String license, String metaLayer, String state) {
    return work(title, license, metaLayer, null, state);
  }

  private static Work abs(String text, String textSource, String jats, String jatsSource) {
    return new Work(1L, null, null, null, "T", null, null, null, null, null, null, null, null, null, null, null,
        text, textSource, "HARVEST", "DISCOVERED", "NONE", null, null, null, Instant.EPOCH, null, jats, null, null,
        null, "CROSSREF", jatsSource, null);
  }

  @Test
  void crossrefTitleBeatsOpenalex() {
    var r = WorkMerger.merge(work("Open title", null, "OPENALEX", "DISCOVERED"), work("Crossref title", null, null,
        "DISCOVERED"), Layer.CROSSREF);
    assertThat(r.work().title()).isEqualTo("Crossref title");
    assertThat(r.work().metaLayer()).isEqualTo("CROSSREF");
    assertThat(r.changed()).isTrue();
  }

  @Test
  void oaiOnlyFillsNullsUnderCrossref() {
    Work stored = work("Crossref title", null, "CROSSREF", "DISCOVERED");
    Work in = new Work(null, null, null, null, "OAI title", null, (short) 2020, null, null, null, null, null, null,
        null, null, null, null, null, "HARVEST", "DISCOVERED", "NONE", null, null, null, null, null, null, null,
        null, null, null, null, null);
    var r = WorkMerger.merge(stored, in, Layer.OAI);
    assertThat(r.work().title()).isEqualTo("Crossref title");
    assertThat(r.work().year()).isEqualTo((short) 2020);
    assertThat(r.work().metaLayer()).isEqualTo("CROSSREF");
  }

  @Test
  void openalexLicenceBeatsCrossref() {
    var r = WorkMerger.merge(work("T", "cc-by", "CROSSREF", "DISCOVERED"), work("X", "cc0", null, "DISCOVERED"),
        Layer.OPENALEX);
    assertThat(r.work().license()).isEqualTo("cc0");
    assertThat(r.work().title()).isEqualTo("T");
    assertThat(r.work().metaLayer()).isEqualTo("CROSSREF");
  }

  @Test
  void titleFrozenAfterNaming() {
    for (String state : new String[] {"NAMES_FOUND", "MATCHED", "INDEXED", "NOTIFIED"}) {
      var r = WorkMerger.merge(work("Old", null, "OPENALEX", state), work("New", null, null, "DISCOVERED"),
          Layer.CROSSREF);
      assertThat(r.work().title()).as(state).isEqualTo("Old");
    }
  }

  @Test
  void jatsAbstractNotReplacedByPlain() {
    Work stored = abs("plain from jats", "CROSSREF_JATS", "<jats/>", "CROSSREF");
    var r = WorkMerger.merge(stored, abs("inverted words", "OPENALEX_INVERTED", null, null), Layer.OPENALEX);
    assertThat(r.work().abstractJats()).isEqualTo("<jats/>");
    assertThat(r.work().abstractText()).isEqualTo("plain from jats");
    assertThat(r.changed()).isFalse();
    // a better JATS kind replaces, and its source goes with it
    var r2 = WorkMerger.merge(abs("t", "EUROPEPMC_JATS", "<epmc/>", "EUROPEPMC"),
        abs("c", "CROSSREF_JATS", "<cr/>", "CROSSREF"), Layer.CROSSREF);
    assertThat(r2.work().abstractJats()).isEqualTo("<cr/>");
    assertThat(r2.work().abstractJatsSource()).isEqualTo("CROSSREF");
    // a worse JATS kind does not
    var r3 = WorkMerger.merge(abs("c", "CROSSREF_JATS", "<cr/>", "CROSSREF"),
        abs("t", "EUROPEPMC_JATS", "<epmc/>", "EUROPEPMC"), Layer.EUROPEPMC);
    assertThat(r3.work().abstractJats()).isEqualTo("<cr/>");
    assertThat(r3.work().abstractJatsSource()).isEqualTo("CROSSREF");
    // plain text beats an inverted index
    var r4 = WorkMerger.merge(abs("inv", "OPENALEX_INVERTED", null, null), abs("plain", "PUBLISHER", null, null),
        Layer.OAI);
    assertThat(r4.work().abstractText()).isEqualTo("plain");
    assertThat(r4.work().abstractSource()).isEqualTo("PUBLISHER");
  }

  @Test
  void emptyNeverReplaces() {
    Work stored = abs("text", "PUBLISHER", "<j/>", "OAI");
    var r = WorkMerger.merge(stored, abs("  ", "CROSSREF_JATS", "", "CROSSREF"), Layer.CROSSREF);
    assertThat(r.work().abstractText()).isEqualTo("text");
    assertThat(r.work().abstractJats()).isEqualTo("<j/>");
    assertThat(r.work().abstractJatsSource()).isEqualTo("OAI");
    Work in = work(null, null, null, "DISCOVERED");
    assertThat(WorkMerger.merge(work("T", "cc0", "OPENALEX", "DISCOVERED"), in, Layer.CROSSREF).work().title())
        .isEqualTo("T");
  }

  @Test
  void originAndStateNeverChange() {
    Work in = new Work(9L, null, null, null, "N", null, null, null, null, null, null, null, null, null, null, null,
        null, null, "BACKFILL", "INDEXED", "FULL", null, null, null, Instant.now(), null, null, null, null, null,
        null, null, null);
    var r = WorkMerger.merge(work("T", null, "OPENALEX", "DISCOVERED"), in, Layer.CROSSREF);
    assertThat(r.work().origin()).isEqualTo("HARVEST");
    assertThat(r.work().state()).isEqualTo("DISCOVERED");
    assertThat(r.work().textLevel()).isEqualTo("NONE");
    assertThat(r.work().firstSeenAt()).isEqualTo(Instant.EPOCH);
    assertThat(r.work().id()).isEqualTo(1L);
  }

  @Test
  void unchangedWhenNothingNew() {
    Work stored = work("T", "cc0", "CROSSREF", "CROSSREF", "DISCOVERED");
    var r = WorkMerger.merge(stored, work("T", "cc0", null, "DISCOVERED"), Layer.CROSSREF);
    assertThat(r.changed()).isFalse();
    assertThat(r.work()).isEqualTo(stored);
  }

  @Test
  void crossrefLicenceDoesNotOverwriteOpenalexLicenceOnCrossrefOwnedWork() {
    Work stored = work("T", "openalex-licence", "CROSSREF", "OPENALEX", "DISCOVERED");
    var r = WorkMerger.merge(stored, work("T", "crossref-licence", null, "DISCOVERED"), Layer.CROSSREF);
    assertThat(r.work().license()).isEqualTo("openalex-licence");
    assertThat(r.work().accessLayer()).isEqualTo("OPENALEX");
    assertThat(r.work().metaLayer()).isEqualTo("CROSSREF");
  }

  @Test
  void accessOwnerTrackedSeparately() {
    // an OpenAlex-owned work: Crossref wins biblio (metaLayer moves) but not access
    Work stored = work("Open", "cc0", "OPENALEX", "OPENALEX", "DISCOVERED");
    var r = WorkMerger.merge(stored, work("Cross", "cc-by", null, "DISCOVERED"), Layer.CROSSREF);
    assertThat(r.work().title()).isEqualTo("Cross");
    assertThat(r.work().metaLayer()).isEqualTo("CROSSREF");
    assertThat(r.work().license()).isEqualTo("cc0");
    assertThat(r.work().accessLayer()).isEqualTo("OPENALEX");
    // OAI (access rank 2) beats an unowned access value and becomes the access owner
    var r2 = WorkMerger.merge(work("T", null, "CROSSREF", "DISCOVERED"), work("T", "cc-by", null, "DISCOVERED"),
        Layer.OAI);
    assertThat(r2.work().license()).isEqualTo("cc-by");
    assertThat(r2.work().accessLayer()).isEqualTo("OAI");
    assertThat(r2.work().metaLayer()).isEqualTo("CROSSREF");
    // a later OpenAlex licence takes over
    var r3 = WorkMerger.merge(r2.work(), work("T", "cc0", null, "DISCOVERED"), Layer.OPENALEX);
    assertThat(r3.work().license()).isEqualTo("cc0");
    assertThat(r3.work().accessLayer()).isEqualTo("OPENALEX");
  }

  @Test
  void abstractOnlyRecordDoesNotClaimBiblioOwner() {
    // an unowned work gets an abstract-only record (e.g. a Crossref JATS abstract): the owner stays open, so a later
    // OpenAlex title still lands
    Work stored = work("Open title", null, null, "DISCOVERED");
    Work in = new Work(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        "text", "CROSSREF_JATS", "HARVEST", "DISCOVERED", "NONE", null, null, null, null, null, "<jats/>", null, null,
        null, null, "CROSSREF", null);
    var r = WorkMerger.merge(stored, in, Layer.CROSSREF);
    assertThat(r.work().abstractJats()).isEqualTo("<jats/>");
    assertThat(r.work().metaLayer()).isNull();
    var r2 = WorkMerger.merge(r.work(), work("Better title", null, null, "DISCOVERED"), Layer.OPENALEX);
    assertThat(r2.work().title()).isEqualTo("Better title");
    assertThat(r2.work().metaLayer()).isEqualTo("OPENALEX");
  }

  @Test
  void openalexTeiFlagFollowsIncomingAndCheckedAtFills() {
    Work stored = new Work(1L, null, null, null, "T", null, null, null, null, null, null, null, null, null, null, null,
        null, null, "HARVEST", "DISCOVERED", "NONE", null, null, null, Instant.EPOCH, null, null, null, true, null,
        "OPENALEX", null, null);
    Work in = new Work(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, "HARVEST", "DISCOVERED", "NONE", null, null, null, null, null, null, null, false,
        Instant.ofEpochSecond(5), null, null, null);
    var r = WorkMerger.merge(stored, in, Layer.OPENALEX);
    assertThat(r.work().openalexHasTei()).isFalse();
    assertThat(r.work().openalexCheckedAt()).isEqualTo(Instant.ofEpochSecond(5));
    // null leaves the flag alone
    assertThat(WorkMerger.merge(stored, work(null, null, null, "DISCOVERED"), Layer.CROSSREF).work().openalexHasTei())
        .isTrue();
  }
}
