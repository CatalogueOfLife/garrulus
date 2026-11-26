package org.catalogueoflife.garrulus.indexer.content.institutional;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ElsevierTdmTest {

  private final ElsevierTdm tdm = new ElsevierTdm("https://api.elsevier.com", "k", null);

  @Test
  void claimsElsevierDois() {
    assertThat(tdm.claims("10.1016/j.ympev.2020.106789")).isTrue();
    assertThat(tdm.claims("10.1016/s0022-2836(05)80360-2")).isTrue();
    assertThat(tdm.claims("10.1016/")).isFalse();
    assertThat(tdm.claims("10.1111/zoj.1")).isFalse();
  }

  @Test
  void refusesDotSegmentsThatWouldLeaveThePath() {
    assertThat(tdm.claims("10.1016/../../admin")).isFalse();
    assertThat(tdm.claims("10.1016/a/../b")).isFalse();
    assertThat(tdm.claims("10.1016/a/./b")).isFalse();
    assertThat(tdm.claims("10.1016/..")).isFalse();
    assertThat(tdm.claims("10.1016/a..b")).isTrue(); // dots inside a segment are fine
  }

  @Test
  void urlKeepsSlashesAndEncodesTheRest() {
    assertThat(tdm.url("10.1016/s0022-2836(05)80360-2").toString()).isEqualTo(
        "https://api.elsevier.com/content/article/doi/10.1016/s0022-2836%2805%2980360-2?httpAccept=text/xml");
  }
}
