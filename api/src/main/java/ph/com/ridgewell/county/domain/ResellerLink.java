package ph.com.ridgewell.county.domain;

import java.time.Duration;
import java.time.Instant;

/** How long a reseller's order link works (ADR 0018). */
public final class ResellerLink {
  public static final Duration LIFETIME = Duration.ofDays(90);

  private ResellerLink() {}

  /** When a link issued at {@code issuedAt} stops working. */
  public static Instant expiry(Instant issuedAt) {
    return issuedAt.plus(LIFETIME);
  }
}
