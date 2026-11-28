package ph.com.ridgewell.county.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ResellerLinkTest {
  private static final Instant ISSUED = Instant.parse("2026-11-28T09:30:00Z");

  @Test
  void aLinkLastsNinetyDays() {
    assertEquals(Duration.ofDays(90), ResellerLink.LIFETIME);
  }

  @Test
  void theExpiryIsTheIssueTimePlusTheLifetime() {
    assertEquals(Instant.parse("2027-02-26T09:30:00Z"), ResellerLink.expiry(ISSUED));
  }

  @Test
  void aLinkWorksBeforeItsExpiry() {
    Instant expiry = ResellerLink.expiry(ISSUED);

    assertTrue(ResellerLink.active(expiry, ISSUED));
    assertTrue(ResellerLink.active(expiry, expiry.minusNanos(1_000)));
  }

  @Test
  void aLinkStopsWorkingAtItsExpiryAndAfter() {
    Instant expiry = ResellerLink.expiry(ISSUED);

    assertFalse(ResellerLink.active(expiry, expiry));
    assertFalse(ResellerLink.active(expiry, expiry.plusSeconds(1)));
  }
}
