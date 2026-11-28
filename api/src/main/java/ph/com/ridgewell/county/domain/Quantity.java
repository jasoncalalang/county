package ph.com.ridgewell.county.domain;

import java.math.BigDecimal;

/** How a quantity leaves the API. */
public final class Quantity {
  private Quantity() {}

  public static BigDecimal wire(BigDecimal quantity) {
    throw new UnsupportedOperationException();
  }
}
