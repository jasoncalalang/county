package ph.com.ridgewell.county.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import ph.com.ridgewell.county.api.Problem;
import ph.com.ridgewell.county.domain.ResellerLink;

/**
 * The reseller's order link: the one place a token is resolved and issued. A token that is
 * malformed, unknown, blocked or expired is the same {@code 404 Not found} (ADR 0003, ADR 0018),
 * so nothing tells a caller which it was. The reseller row is the first lock of every workflow
 * (ADR 0013).
 */
@ApplicationScoped
public class ResellerLinks {
  private static final SecureRandom RANDOM = new SecureRandom();

  @Inject Clock clock;

  /** The reseller behind a usable link, locked as asked, or 404. */
  public Reseller resolve(String token, LockModeType lock) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{22}")) throw new Problem(404, "Not found");
    return Reseller.byToken(token, Times.now(clock), lock)
        .orElseThrow(() -> new Problem(404, "Not found"));
  }

  /** Gives a new reseller its first token and the expiry that goes with it. */
  void issue(Reseller reseller) {
    reseller.token = newToken();
    reseller.tokenExpiresAt = ResellerLink.expiry(Times.now(clock));
  }

  /** A 128-bit random token in 22 URL-safe characters. */
  String newToken() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
