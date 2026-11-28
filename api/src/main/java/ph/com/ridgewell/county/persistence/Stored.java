package ph.com.ridgewell.county.persistence;

import java.util.Locale;

/** How an enum is stored: its lowercase name. The only place that converts. */
final class Stored {
  private Stored() {}

  static String of(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }
}
