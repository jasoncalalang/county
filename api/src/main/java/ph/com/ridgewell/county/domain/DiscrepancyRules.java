package ph.com.ridgewell.county.domain;

import java.math.BigDecimal;
import java.util.List;
import ph.com.ridgewell.county.domain.OrderRules.Actor;

/**
 * Short-delivery reports (ADR 0012): what the shortfall of a line is, and who may resolve a
 * report. A report never changes the order it is about.
 */
public final class DiscrepancyRules {
  private DiscrepancyRules() {}

  public enum Status {
    OPEN,
    APPROVED,
    DECLINED;

    public String wire() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * {@code (ordered - received) * price} rounded half up to centavos, sign kept: negative is
   * over-delivery. Refuses equal quantities and a magnitude {@code numeric(12,2)} cannot hold.
   */
  public static BigDecimal shortfall(BigDecimal ordered, BigDecimal received, BigDecimal price) {
    throw new UnsupportedOperationException();
  }

  /** The sum of the line shortfalls; refuses a magnitude {@code numeric(12,2)} cannot hold. */
  public static BigDecimal total(List<BigDecimal> lineShortfalls) {
    throw new UnsupportedOperationException();
  }

  /**
   * Resolving: only an open report, only to approved or declined, and only by the party that did
   * not file it. Returns the target.
   */
  public static Status resolve(Status current, Actor filedBy, Actor resolver, Status target) {
    throw new UnsupportedOperationException();
  }
}
