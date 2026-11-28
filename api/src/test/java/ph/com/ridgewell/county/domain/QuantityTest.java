package ph.com.ridgewell.county.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuantityTest {
  private static String wire(String quantity) {
    return Quantity.wire(new BigDecimal(quantity)).toString();
  }

  @Test
  void trailingZerosAreDropped() {
    assertEquals("10", wire("10.000"));
    assertEquals("1.5", wire("1.500"));
    assertEquals("0.333", wire("0.333"));
    assertEquals("2", wire("2"));
  }

  @Test
  void aWholeQuantityNeverUsesExponentNotation() {
    assertEquals("1000", wire("1000"));
    assertEquals("1000", wire("1E+3"));
    assertEquals("9999", wire("9999.000"));
  }
}
