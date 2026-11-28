package ph.com.ridgewell.county.domain;

import java.math.BigDecimal;
import java.util.Locale;

/** The unit a product is sold in. Only weight takes a fractional quantity (ADR 0017). */
public enum Unit {
  PACK,
  KG,
  PC;

  /** The unit as stored in {@code product.unit}: lowercase, one of pack, kg, pc. */
  public static Unit of(String stored) {
    if (stored != null)
      for (Unit unit : values()) if (unit.name().toLowerCase(Locale.ROOT).equals(stored)) return unit;
    throw new IllegalArgumentException("Unknown unit: " + stored);
  }

  public boolean allows(BigDecimal quantity) {
    return this == KG || quantity.stripTrailingZeros().scale() <= 0;
  }
}
