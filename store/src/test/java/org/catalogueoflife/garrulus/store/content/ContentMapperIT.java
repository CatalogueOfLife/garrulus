package org.catalogueoflife.garrulus.store.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.garrulus.store.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.work.Work;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ContentMapperIT extends AbstractPostgresIT {

  @Autowired ContentMapper contents;
  @Autowired WorkMapper works;

  @Test
  void upsertReplacesSameRoute() {
    long workId = works.insert(new Work(null, "W-content", null, null, "t", null, null, null, null, null, "[]", null,
        null, null, null, null, null, null, "LIVE", "DISCOVERED", null, java.util.List.of(), java.util.List.of(),
        false, null, null, null, null, false, null, null, null, null));
    long first = contents.upsert(
        new Content(null, workId, "PMC", "JATS", "cc-by", true, "raw1", "doc1", "https://a", null));
    long second = contents.upsert(
        new Content(null, workId, "PMC", "JATS", "cc-by-nc", false, "raw2", "doc2", "https://b", null));
    contents.upsert(new Content(null, workId, "ABSTRACT", "ABSTRACT", null, true, null, "doc3", null, null));
    assertThat(second).isEqualTo(first);
    var list = contents.listByWork(workId);
    assertThat(list).hasSize(2);
    Content pmc = list.stream().filter(c -> c.route().equals("PMC")).findFirst().orElseThrow();
    assertThat(pmc.license()).isEqualTo("cc-by-nc");
    assertThat(pmc.retained()).isFalse();
    assertThat(pmc.rawSha256()).isEqualTo("raw2");
    assertThat(pmc.docSha256()).isEqualTo("doc2");
    assertThat(pmc.sourceUrl()).isEqualTo("https://b");
    assertThat(pmc.fetchedAt()).isNotNull();
  }
}
