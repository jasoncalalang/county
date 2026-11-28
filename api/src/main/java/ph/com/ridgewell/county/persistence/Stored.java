package ph.com.ridgewell.county.persistence;

import java.util.Locale;

/**
 * An enum as the status and actor columns store it: its lowercase name. {@link
 * SalesOrder#moveTo} and {@link OrderHistory} write through it; {@link SalesOrder#currentStatus()}
 * reads an order's status back. The older list filter in {@link Orders} converts on its own.
 */
final class Stored {
  private Stored() {}

  static String of(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }
}
