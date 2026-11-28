package ph.com.ridgewell.county.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UnitTest {
  @Test
  void storedUnitsMapToTheEnum() {
    assertEquals(Unit.PACK, Unit.of("pack"));
    assertEquals(Unit.KG, Unit.of("kg"));
    assertEquals(Unit.PC, Unit.of("pc"));
  }

  @Test
  void anUnknownStoredUnitIsRefused() {
    for (String stored : new String[] {"", "KG", "box", "g", null})
      assertThrows(IllegalArgumentException.class, () -> Unit.of(stored), String.valueOf(stored));
  }

  @Test
  void weightTakesFractions() {
    assertTrue(Unit.KG.allows(new BigDecimal("1.5")));
    assertTrue(Unit.KG.allows(new BigDecimal("0.001")));
    assertTrue(Unit.KG.allows(new BigDecimal("2")));
  }

  @Test
  void packsAndPiecesAreWholeNumbers() {
    for (Unit unit : new Unit[] {Unit.PACK, Unit.PC}) {
      assertTrue(unit.allows(new BigDecimal("2")), unit.name());
      assertTrue(unit.allows(new BigDecimal("2.000")), unit.name());
      assertTrue(unit.allows(new BigDecimal("1E+3")), unit.name());
      assertFalse(unit.allows(new BigDecimal("1.5")), unit.name());
      assertFalse(unit.allows(new BigDecimal("0.001")), unit.name());
    }
  }
}
