package ph.com.ridgewell.county.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.com.ridgewell.county.domain.DiscrepancyRules.Status;
import ph.com.ridgewell.county.domain.OrderRules.Actor;

class DiscrepancyRulesTest {
  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  @Test
  void aShortLineIsTheMissingQuantityTimesTheCapturedPrice() {
    assertEquals(d("240.00"), DiscrepancyRules.shortfall(d("10"), d("8"), d("120.00")));
    assertEquals(d("120.00"), DiscrepancyRules.shortfall(d("10"), d("9"), d("120")));
  }

  @Test
  void aLineRoundsHalfUpToCentavos() {
    // 0.25 * 99.99 = 24.9975
    assertEquals(d("25.00"), DiscrepancyRules.shortfall(d("1.5"), d("1.25"), d("99.99")));
    // 0.005 * 1.00 = 0.005 rounds up to 0.01, not down to 0.00
    assertEquals(d("0.01"), DiscrepancyRules.shortfall(d("1.005"), d("1"), d("1.00")));
    // 0.001 * 1.00 = 0.001 rounds to 0.00
    assertEquals(d("0.00"), DiscrepancyRules.shortfall(d("1.001"), d("1"), d("1.00")));
  }

  @Test
  void overDeliveryIsANegativeShortfallRoundedHalfUpAwayFromZero() {
    assertEquals(d("-240.00"), DiscrepancyRules.shortfall(d("10"), d("12"), d("120")));
    // -0.005 rounds half up to -0.01: sign kept, magnitude rounded up
    assertEquals(d("-0.01"), DiscrepancyRules.shortfall(d("1"), d("1.005"), d("1.00")));
  }

  @Test
  void aQuantityThatEqualsTheOrderedOneIsRefusedWhateverItsScale() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscrepancyRules.shortfall(d("10"), d("10"), d("120")));
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscrepancyRules.shortfall(d("1.500"), d("1.5"), d("120")));
  }

  @Test
  void aShortfallThatNumeric12Dot2CannotHoldIsRefusedInEitherDirection() {
    var ceiling = d("9999999999.99");
    // 9999 kg ordered, none received, at the ceiling price
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscrepancyRules.shortfall(d("9999"), d("0"), ceiling));
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscrepancyRules.shortfall(d("1"), d("9999"), ceiling));
    // Exactly at the ceiling is allowed: 1 * ceiling
    assertEquals(ceiling, DiscrepancyRules.shortfall(d("1"), d("0"), ceiling));
    assertEquals(ceiling.negate(), DiscrepancyRules.shortfall(d("0"), d("1"), ceiling));
  }

  @Test
  void theTotalIsTheSumOfTheLinesAndIsHeldToTheSameCeiling() {
    assertEquals(
        d("215.00"), DiscrepancyRules.total(List.of(d("240.00"), d("-25.00"))));
    assertEquals(d("0.00"), DiscrepancyRules.total(List.of(d("5.00"), d("-5.00"))));
    var ceiling = d("9999999999.99");
    assertEquals(ceiling, DiscrepancyRules.total(List.of(ceiling, d("0.00"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscrepancyRules.total(List.of(ceiling, d("0.01"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscrepancyRules.total(List.of(ceiling.negate(), d("-0.01"))));
  }

  @Test
  void statusesAreLowercaseOnTheWire() {
    assertEquals("open", Status.OPEN.wire());
    assertEquals("approved", Status.APPROVED.wire());
    assertEquals("declined", Status.DECLINED.wire());
  }

  @Test
  void onlyTheOtherPartyResolvesAnOpenReportToApprovedOrDeclined() {
    for (Actor filedBy : Actor.values())
      for (Actor resolver : Actor.values())
        for (Status target : Status.values()) {
          boolean legal =
              filedBy != resolver && (target == Status.APPROVED || target == Status.DECLINED);
          if (legal)
            assertEquals(target, DiscrepancyRules.resolve(Status.OPEN, filedBy, resolver, target));
          else
            assertThrows(
                IllegalArgumentException.class,
                () -> DiscrepancyRules.resolve(Status.OPEN, filedBy, resolver, target),
                filedBy + " " + resolver + " " + target);
        }
  }

  @Test
  void aResolvedReportCanNeverBeResolvedAgain() {
    for (Status current : List.of(Status.APPROVED, Status.DECLINED))
      for (Status target : Status.values())
        assertThrows(
            IllegalArgumentException.class,
            () -> DiscrepancyRules.resolve(current, Actor.RESELLER, Actor.OWNER, target));
  }

  @Test
  void theFilerResolvingTheirOwnReportIsToldSo() {
    var own =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DiscrepancyRules.resolve(
                    Status.OPEN, Actor.OWNER, Actor.OWNER, Status.APPROVED));
    assertEquals("Only the other party can resolve this report", own.getMessage());
    var again =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DiscrepancyRules.resolve(
                    Status.APPROVED, Actor.RESELLER, Actor.OWNER, Status.DECLINED));
    assertEquals("This report is now approved", again.getMessage());
  }
}
