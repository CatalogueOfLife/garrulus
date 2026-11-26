package org.catalogueoflife.garrulus.finder.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PipelineConfigTest {

  /** A finder-core change that should reprocess the works bumps the epoch, which changes the hash. */
  @Test
  void epochChangesHash() {
    PipelineConfig a = PipelineConfig.defaults();
    assertThat(a.epoch()).isEqualTo(1);
    PipelineConfig b = a.withOverrides("{\"epoch\": 2}");
    assertThat(b.epoch()).isEqualTo(2);
    assertThat(b.withOverrides("{\"epoch\": 1}")).isEqualTo(a);
    assertThat(b.configHash()).isNotEqualTo(a.configHash());
  }
}
