package ph.com.ridgewell.county.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * numeric(12,2) arithmetic. A submitted price is never rounded; a line total is unit price times
 * quantity rounded half up to centavos (ADR 0017), and an order total is the sum of those lines.
 */
public final class Money {
  private static final BigDecimal MAXIMUM = new BigDecimal("9999999999.99");
  private static final BigDecimal MAXIMUM_QUANTITY = new BigDecimal("9999");
  private static final BigDecimal ONE_CENTAVO = new BigDecimal("0.01");

  private Money() {}

  /** Quantity is above 0 and at most 9999 with up to three decimals; the line is at least 0.01. */
  public static BigDecimal line(BigDecimal price, BigDecimal quantity) {
    if (price == null || price.signum() <= 0 || price.compareTo(MAXIMUM) > 0)
      throw new IllegalArgumentException("A positive price is required");
    // Bounds first, on the parsed decimal: an exponent such as 1e400 is never expanded.
    if (quantity == null
        || quantity.signum() <= 0
        || quantity.compareTo(MAXIMUM_QUANTITY) > 0
        || quantity.stripTrailingZeros().scale() > 3)
      throw new IllegalArgumentException(
          "Quantity must be above 0 and at most 9999, with up to three decimals");
    final BigDecimal centavos;
    try {
      centavos = price.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Price must have no fractions of a centavo", e);
    }
    BigDecimal total = centavos.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    if (total.compareTo(ONE_CENTAVO) < 0)
      throw new IllegalArgumentException("A line must come to at least 0.01");
    if (total.compareTo(MAXIMUM) > 0)
      throw new IllegalArgumentException("Line total is too large");
    return total;
  }

  public static BigDecimal line(BigDecimal price, int quantity) {
    if (price == null
        || price.signum() <= 0
        || price.compareTo(MAXIMUM) > 0
        || quantity < 1
        || quantity > 9999) {
      throw new IllegalArgumentException(
          "A positive price and quantity from 1 to 9999 are required");
    }
    final BigDecimal centavos;
    try {
      centavos = price.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Price must have no fractions of a centavo", e);
    }
    BigDecimal total = centavos.multiply(BigDecimal.valueOf(quantity));
    if (total.compareTo(MAXIMUM) > 0)
      throw new IllegalArgumentException("Line total exceeds numeric(12,2)");
    return total;
  }
}
