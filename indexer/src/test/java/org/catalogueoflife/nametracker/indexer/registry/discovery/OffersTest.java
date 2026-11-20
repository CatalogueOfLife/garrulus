package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.registry.Candidate;
import org.catalogueoflife.nametracker.indexer.registry.CandidateRegistry;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.junit.jupiter.api.Test;

class OffersTest {

  static final Candidate C = new Candidate("BLR", "", List.of("1175-5326"), "J", null, null, null, "{}", List.of(), null);

  @Test
  void anOutageWrappedInAnotherExceptionStillDefersTheTask() {
    CandidateRegistry registry = mock(CandidateRegistry.class);
    ServiceOutageException outage = new ServiceOutageException("database (deadlock)", Instant.parse("2026-11-20T12:00:00Z"));
    when(registry.offer(any(), any())).thenThrow(new IllegalStateException("wrapped", outage));
    Offers offers = new Offers(registry);
    assertThatThrownBy(() -> offers.offer(C)).isSameAs(outage);
  }

  @Test
  void aDataErrorIsCountedAndSkipped() {
    CandidateRegistry registry = mock(CandidateRegistry.class);
    when(registry.offer(any(), any())).thenThrow(new IllegalArgumentException("bad row"));
    Offers offers = new Offers(registry);
    offers.offer(C);
    assertThat(offers.summary()).contains("1 failed");
  }
}
