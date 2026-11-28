package ph.com.ridgewell.county.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
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
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * {@code (ordered - received) * price} rounded half up to centavos, sign kept: negative is
   * over-delivery. Refuses equal quantities and a magnitude {@code numeric(12,2)} cannot hold.
   */
  public static BigDecimal shortfall(BigDecimal ordered, BigDecimal received, BigDecimal price) {
    if (ordered.compareTo(received) == 0)
      throw new IllegalArgumentException("Received quantity matches what was ordered");
    return withinCeiling(
        ordered.subtract(received).multiply(price).setScale(2, RoundingMode.HALF_UP));
  }

  /** The sum of the line shortfalls; refuses a magnitude {@code numeric(12,2)} cannot hold. */
  public static BigDecimal total(List<BigDecimal> lineShortfalls) {
    BigDecimal total = new BigDecimal("0.00");
    for (BigDecimal line : lineShortfalls) total = withinCeiling(total.add(line));
    return total;
  }

  private static BigDecimal withinCeiling(BigDecimal amount) {
    if (amount.abs().compareTo(Money.MAXIMUM) > 0)
      throw new IllegalArgumentException("Shortfall is too large");
    return amount;
  }

  /**
   * Resolving: only an open report, only to approved or declined, and only by the party that did
   * not file it. Returns the target.
   */
  public static Status resolve(Status current, Actor filedBy, Actor resolver, Status target) {
    if (current == null || filedBy == null || resolver == null || target == null)
      throw new IllegalArgumentException("Status and parties are required");
    if (current != Status.OPEN)
      throw new IllegalArgumentException("This report is now " + current.wire());
    if (filedBy == resolver)
      throw new IllegalArgumentException("Only the other party can resolve this report");
    if (target == Status.OPEN)
      throw new IllegalArgumentException("A report is resolved as approved or declined");
    return target;
  }
}
