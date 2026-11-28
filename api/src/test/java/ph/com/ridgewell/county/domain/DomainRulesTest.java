package ph.com.ridgewell.county.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DomainRulesTest {
  private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
  private static final LocalTime CUTOFF = LocalTime.of(16, 0);
  private static final Set<DayOfWeek> MON_SAT = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY);

  private static Instant at(String local) {
    return LocalDateTime.parse(local).atZone(MANILA).toInstant();
  }

  @Test
  void cutoffUsesBusinessTimezoneAndIncludesExactBoundary() {
    assertEquals(
        LocalDate.of(2026, 11, 17),
        DeliveryDates.compute(at("2026-11-16T15:59"), MANILA, CUTOFF, MON_SAT));
    assertEquals(
        LocalDate.of(2026, 11, 18),
        DeliveryDates.compute(at("2026-11-16T16:00"), MANILA, CUTOFF, MON_SAT));
    assertEquals(
        LocalDate.of(2026, 11, 18),
        DeliveryDates.compute(at("2026-11-16T16:01"), MANILA, CUTOFF, MON_SAT));
  }

  @Test
  void skipsNonDeliveryDays() {
    assertEquals(
        LocalDate.of(2026, 11, 16),
        DeliveryDates.compute(at("2026-11-14T10:00"), MANILA, CUTOFF, MON_SAT));
    assertEquals(
        LocalDate.of(2026, 11, 16),
        DeliveryDates.compute(at("2026-11-14T16:30"), MANILA, CUTOFF, MON_SAT));
    var mwf = EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
    assertEquals(
        LocalDate.of(2026, 11, 18),
        DeliveryDates.compute(at("2026-11-16T10:00"), MANILA, CUTOFF, mwf));
    assertEquals(
        LocalDate.of(2026, 11, 18),
        DeliveryDates.compute(at("2026-11-16T16:00"), MANILA, CUTOFF, mwf));
    assertEquals(
        LocalDate.of(2026, 11, 20),
        DeliveryDates.compute(at("2026-11-18T15:59"), MANILA, CUTOFF, mwf));
    assertEquals(
        LocalDate.of(2026, 11, 23),
        DeliveryDates.compute(at("2026-11-20T16:00"), MANILA, CUTOFF, mwf));
  }

  @Test
  void handlesYearBoundaryAndSingleDeliveryDay() {
    assertEquals(
        LocalDate.of(2027, 1, 4),
        DeliveryDates.compute(
            at("2026-12-31T16:00"), MANILA, CUTOFF, EnumSet.of(DayOfWeek.MONDAY)));
  }

  @Test
  void midnightCutoffNeverSelectsTomorrow() {
    assertEquals(
        LocalDate.of(2026, 11, 18),
        DeliveryDates.compute(at("2026-11-16T00:00"), MANILA, LocalTime.MIDNIGHT, MON_SAT));
  }

  @Test
  void rejectsMissingDateInputs() {
    assertThrows(
        IllegalArgumentException.class, () -> DeliveryDates.compute(null, MANILA, CUTOFF, MON_SAT));
    assertThrows(
        IllegalArgumentException.class,
        () -> DeliveryDates.compute(Instant.now(), null, CUTOFF, MON_SAT));
    assertThrows(
        IllegalArgumentException.class,
        () -> DeliveryDates.compute(Instant.now(), MANILA, null, MON_SAT));
    Set<DayOfWeek> malformed = new HashSet<>();
    malformed.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> DeliveryDates.compute(Instant.now(), MANILA, CUTOFF, malformed));
  }

  @Test
  void rejectsMissingDeliveryDaysInsteadOfLooping() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DeliveryDates.compute(Instant.now(), MANILA, CUTOFF, Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> DeliveryDates.compute(Instant.now(), MANILA, CUTOFF, null));
  }

  @Test
  void normalizesAllAcceptedMobileForms() {
    for (String value :
        List.of(
            "09171234567",
            "9171234567",
            "+639171234567",
            "639171234567",
            "0917 123 4567",
            "+63-917-123-4567",
            " 0917 123 4567 ")) {
      assertEquals("+639171234567", PhilippineMobile.normalize(value), value);
    }
  }

  @Test
  void rejectsMalformedMobileNumbers() {
    for (String value :
        List.of(
            "",
            "0917123456",
            "091712345678",
            "08171234567",
            "+629171234567",
            "0917abc4567",
            "(0917)1234567",
            "００９１７１２３４５６７",
            "0917\n1234567",
            "++639171234567")) {
      assertThrows(IllegalArgumentException.class, () -> PhilippineMobile.normalize(value), value);
    }
    assertThrows(IllegalArgumentException.class, () -> PhilippineMobile.normalize(null));
  }

  @Test
  void allowsOnlySpecifiedStatusTransitionsForEachActor() {
    for (var actor : OrderRules.Actor.values())
      for (var from : OrderRules.Status.values())
        for (var to : OrderRules.Status.values()) {
          boolean allowed =
              from == OrderRules.Status.PLACED && to == OrderRules.Status.CANCELLED
                  || actor == OrderRules.Actor.OWNER
                      && (from == OrderRules.Status.PLACED && to == OrderRules.Status.CONFIRMED
                          || from == OrderRules.Status.CONFIRMED
                              && (to == OrderRules.Status.DELIVERED
                                  || to == OrderRules.Status.CANCELLED));
          if (allowed) assertEquals(to, OrderRules.transition(from, to, actor));
          else
            assertThrows(
                IllegalArgumentException.class,
                () -> OrderRules.transition(from, to, actor),
                actor + " " + from + " -> " + to);
        }
  }

  @Test
  void rejectsMissingTransitionInputs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OrderRules.transition(null, OrderRules.Status.CONFIRMED, OrderRules.Actor.OWNER));
    assertThrows(
        IllegalArgumentException.class,
        () -> OrderRules.transition(OrderRules.Status.PLACED, null, OrderRules.Actor.OWNER));
    assertThrows(
        IllegalArgumentException.class,
        () -> OrderRules.transition(OrderRules.Status.PLACED, OrderRules.Status.CANCELLED, null));
  }

  @Test
  void moneyUsesExactCentavosAndBounds() {
    assertEquals(new BigDecimal("1200.00"), Money.line(new BigDecimal("120"), new BigDecimal("10")));
    assertEquals(new BigDecimal("0.30"), Money.line(new BigDecimal("0.10"), new BigDecimal("3")));
    assertEquals(new BigDecimal("3.60"), Money.line(new BigDecimal("1.200"), new BigDecimal("3")));
    assertEquals(new BigDecimal("99.99"), Money.line(new BigDecimal("0.01"), new BigDecimal("9999")));
    assertEquals(new BigDecimal("9999999999.99"), Money.line(new BigDecimal("9999999999.99"), new BigDecimal("1")));
  }

  @Test
  void rejectsMoneyOverflowRoundingAndInvalidQuantities() {
    for (BigDecimal value :
        List.of(
            BigDecimal.ZERO,
            new BigDecimal("-1"),
            new BigDecimal("0.001"),
            new BigDecimal("10000000000"))) {
      assertThrows(IllegalArgumentException.class, () -> Money.line(value, BigDecimal.ONE));
    }
    assertThrows(
        IllegalArgumentException.class, () -> Money.line(new BigDecimal("9999999999.99"), new BigDecimal("2")));
    for (int qty : new int[] {-1, 0, 10000, Integer.MAX_VALUE})
      assertThrows(IllegalArgumentException.class, () -> Money.line(BigDecimal.ONE, BigDecimal.valueOf(qty)));
    assertThrows(IllegalArgumentException.class, () -> Money.line(null, BigDecimal.ONE));
  }

  @Test
  void fractionalQuantitiesRoundHalfUpToCentavos() {
    assertEquals(new BigDecimal("180.00"), Money.line(new BigDecimal("120.00"), new BigDecimal("1.5")));
    assertEquals(new BigDecimal("39.96"), Money.line(new BigDecimal("120.00"), new BigDecimal("0.333")));
    // 99.99 x 0.333 = 33.29667: more than half a centavo over 33.29.
    assertEquals(new BigDecimal("33.30"), Money.line(new BigDecimal("99.99"), new BigDecimal("0.333")));
    // 0.01 x 0.5 = 0.005: exactly half rounds up.
    assertEquals(new BigDecimal("0.01"), Money.line(new BigDecimal("0.01"), new BigDecimal("0.5")));
    assertEquals(new BigDecimal("1200.00"), Money.line(new BigDecimal("120"), new BigDecimal("10.000")));
  }

  @Test
  void rejectsFractionalQuantitiesOutsideTheRule() {
    BigDecimal price = new BigDecimal("120.00");
    for (String quantity :
        new String[] {
          "0", "-1", "-0.001", "9999.001", "10000", "1.0005", "1e400", "1e-400", "1E+5"
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> Money.line(price, new BigDecimal(quantity)),
          quantity);
    assertThrows(IllegalArgumentException.class, () -> Money.line(price, (BigDecimal) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> Money.line(new BigDecimal("1.005"), new BigDecimal("1.5")),
        "a price is never rounded");
    assertThrows(
        IllegalArgumentException.class,
        () -> Money.line(new BigDecimal("9999999999.99"), new BigDecimal("1.5")),
        "total above the ceiling");
    assertEquals(
        new BigDecimal("1199880.00"), Money.line(new BigDecimal("120.00"), new BigDecimal("9999")));
  }

  @Test
  void refusesALineThatRoundsBelowOneCentavo() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Money.line(new BigDecimal("1.00"), new BigDecimal("0.004")));
    assertThrows(
        IllegalArgumentException.class,
        () -> Money.line(new BigDecimal("0.01"), new BigDecimal("0.001")));
    assertEquals(new BigDecimal("0.01"), Money.line(new BigDecimal("1.00"), new BigDecimal("0.005")));
  }
}
