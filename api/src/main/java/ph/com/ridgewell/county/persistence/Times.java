package ph.com.ridgewell.county.persistence;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Wire formats for times, matching what PostgreSQL stores. */
final class Times {
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

  private Times() {}

  /** Now, at the microsecond precision of a PostgreSQL timestamp. */
  static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  /** The clock's now, at the microsecond precision of a PostgreSQL timestamp. */
  static Instant now(Clock clock) {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }

  static String clock(LocalTime time) {
    return time.format(CLOCK);
  }

  static String instant(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
