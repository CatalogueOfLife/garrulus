package org.catalogueoflife.nametracker.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MapperIT extends AbstractPostgresIT {

  @Autowired SourceMapper sources;
  @Autowired WorkMapper works;

  private Source source(String openalexId, String title) {
    return new Source(0L, openalexId, "1234-5678", List.of("1234-5678", "8765-4321"), title, "Pensoft", "host",
        true, true, "CORE", "MANUAL", "{\"adapter\": \"x\"}", true, List.of("sf1"), "sf1", "TAXONOMIC",
        List.of("Aves"), "{\"Aves\": 0.5}", List.of("ICZN"), "DIAMOND", "BG", 0.25f, 0.5f, 120, null, null, null, null, "SEED");
  }

  @Test
  void sourceRoundTripAndUpdate() {
    long id = sources.insert(source("S-roundtrip", "ZooKeys"));
    Source s = sources.get(id);
    assertThat(s.id()).isEqualTo(id);
    assertThat(s.title()).isEqualTo("ZooKeys");
    assertThat(s.issns()).containsExactly("1234-5678", "8765-4321");
    assertThat(s.taxgroups()).containsExactly("Aves");
    assertThat(s.adapterConfig()).contains("adapter");
    assertThat(s.taxgroupShares()).contains("Aves");
    assertThat(s.nameDensity()).isEqualTo(0.25f);
    assertThat(s.worksPerYear()).isEqualTo(120);
    assertThat(s.created()).isNotNull();
    assertThat(s.lastHarvested()).isNull();

    Source changed = new Source(id, s.openalexId(), s.issnL(), s.issns(), "ZooKeys 2", s.publisher(), s.host(),
        s.isOa(), s.inDoaj(), "EXCLUDED", s.origin(), s.adapterConfig(), false, List.of(), null, null, List.of(),
        "{}", List.of(), null, null, null, null, null, null, null, null, null, "SEED");
    assertThat(sources.update(changed)).isEqualTo(1);
    Source u = sources.get(id);
    assertThat(u.title()).isEqualTo("ZooKeys 2");
    assertThat(u.tier()).isEqualTo("EXCLUDED");
    assertThat(u.active()).isFalse();
    assertThat(u.issns()).containsExactly("1234-5678", "8765-4321");
    assertThat(u.subfields()).isEmpty();
    assertThat(u.modified()).isAfterOrEqualTo(u.created());
  }

  @Test
  void workRoundTripAndUpdate() {
    long sourceId = sources.insert(source("S-work", "Phytotaxa"));
    Work w = new Work(0L, "W-roundtrip", "10.1/rt", sourceId, "A title", LocalDate.of(2026, 5, 17), (short) 2026,
        "12", "3", "1-10", "[{\"name\": \"Doe\"}]", "article", "en", "gold", "cc-by", "https://x/y", "An abstract",
        "CROSSREF_JATS", "LIVE", "DISCOVERED", null, List.of("ICN"), List.of("Plantae"), false, null, null, null, null, false, null, null, null);
    long id = works.insert(w);
    Work got = works.get(id);
    assertThat(got.doi()).isEqualTo("10.1/rt");
    assertThat(got.sourceId()).isEqualTo(sourceId);
    assertThat(got.publicationDate()).isEqualTo(LocalDate.of(2026, 5, 17));
    assertThat(got.year()).isEqualTo((short) 2026);
    assertThat(got.authors()).contains("Doe");
    assertThat(got.codes()).containsExactly("ICN");
    assertThat(got.state()).isEqualTo("DISCOVERED");
    assertThat(got.firstSeenAt()).isNotNull();

    Work next = new Work(id, got.openalexId(), got.doi(), got.sourceId(), got.title(), got.publicationDate(),
        got.year(), got.volume(), got.issue(), got.pages(), got.authors(), got.type(), got.language(),
        got.oaStatus(), got.license(), got.bestOaUrl(), got.abstractText(), got.abstractSource(), got.origin(),
        "MATCHED", "FULLTEXT", got.codes(), got.taxgroups(), true, null, null, null, null, false, null, null, null);
    assertThat(works.update(next)).isEqualTo(1);
    Work u = works.get(id);
    assertThat(u.state()).isEqualTo("MATCHED");
    assertThat(u.textLevel()).isEqualTo("FULLTEXT");
    assertThat(u.hasActs()).isTrue();
  }
}
