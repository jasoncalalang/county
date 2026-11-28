package ph.com.ridgewell.county.domain;

import java.math.BigDecimal;

/** How a quantity leaves the API. */
public final class Quantity {
  private Quantity() {}

  public static BigDecimal wire(BigDecimal quantity) {
    BigDecimal stripped = quantity.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }
}
