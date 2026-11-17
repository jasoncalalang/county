package ph.com.ridgewell.county.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact numeric(12,2) arithmetic. Never rounds a submitted price silently. */
public final class Money {
  private static final BigDecimal MAXIMUM = new BigDecimal("9999999999.99");

  private Money() {}

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
