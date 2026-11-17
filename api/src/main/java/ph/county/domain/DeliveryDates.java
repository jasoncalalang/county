package ph.county.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/** Business-calendar delivery dates; deliberately independent of the system timezone. */
public final class DeliveryDates {
  private DeliveryDates() {}

  public static LocalDate compute(Instant now, ZoneId zone, LocalTime cutoff, Set<DayOfWeek> days) {
    if (now == null
        || zone == null
        || cutoff == null
        || days == null
        || days.isEmpty()
        || days.stream().anyMatch(d -> d == null)) {
      throw new IllegalArgumentException(
          "A time, timezone, cut-off and at least one delivery day are required");
    }
    var local = now.atZone(zone);
    LocalDate date = local.toLocalDate().plusDays(local.toLocalTime().isBefore(cutoff) ? 1 : 2);
    for (int i = 0; i < 7; i++, date = date.plusDays(1)) {
      if (days.contains(date.getDayOfWeek())) return date;
    }
    throw new IllegalArgumentException("At least one valid delivery day is required");
  }
}
