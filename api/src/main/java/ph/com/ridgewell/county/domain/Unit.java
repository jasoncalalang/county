package ph.com.ridgewell.county.domain;

import java.math.BigDecimal;

/** The unit a product is sold in. Only weight takes a fractional quantity (ADR 0017). */
public enum Unit {
  PACK,
  KG,
  PC;

  public static Unit of(String stored) {
    throw new UnsupportedOperationException();
  }

  public boolean allows(BigDecimal quantity) {
    throw new UnsupportedOperationException();
  }
}
