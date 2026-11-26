package org.catalogueoflife.garrulus.finder.salience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.finder.salience.SalienceInputs.MentionView;
import org.junit.jupiter.api.Test;

class SalienceInputsTest {

  private static MentionView m(String n, String b) {
    return new MentionView(n, b, null);
  }

  @Test
  void flagsCountsAndMax() {
    var in = SalienceInputs.of(List.of(m("A", "TITLE"), m("A", "BODY"), m("A", "HEADING"), m("A", "REFERENCE"),
        m("B", "BODY"), m("B", "BODY"), m("B", "BODY"), m("B", "ABSTRACT")), false, Map.of("B", 0.1));
    var a = in.get("A");
    assertTrue(a.inTitle());
    assertTrue(a.inHeading());
    assertFalse(a.inAbstract());
    assertEquals(3, a.bodyCount());
    assertEquals(4, a.maxBodyCountInWork());
    assertEquals(0.0, a.ubiquityShare());
    var b = in.get("B");
    assertTrue(b.inAbstract());
    assertEquals(4, b.bodyCount());
    assertEquals(0.1, b.ubiquityShare());
  }

  @Test
  void referencesDoNotCount() {
    var in = SalienceInputs.of(List.of(new MentionView("R", "REFERENCE", "NEW_COMBINATION"), m("R", "REFERENCE"),
        m("X", "BODY")), true, Map.of());
    var r = in.get("R");
    assertEquals(0, r.bodyCount());
    assertFalse(r.hasAct());
    assertFalse(r.inAbstract());
    assertEquals(1, r.maxBodyCountInWork());
  }

  @Test
  void actOnlyFromBodyMentions() {
    var in = SalienceInputs.of(List.of(new MentionView("A", "BODY", "SP_NOV"), new MentionView("C", "REFERENCE", "SP_NOV"),
        m("C", "BODY")), false, Map.of());
    assertTrue(in.get("A").hasAct());
    assertFalse(in.get("C").hasAct());
  }

  @Test
  void abstractOnlyMakesBodyMentionsAbstract() {
    var in = SalienceInputs.of(List.of(m("A", "BODY")), true, Map.of());
    assertTrue(in.get("A").inAbstract());
  }
}
