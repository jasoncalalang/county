package ph.com.ridgewell.county.domain;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Quote status changes. A quote changes as a whole row; its lines never change. Expiry is read,
 * not stored: a sent quote whose last day has passed is expired (there is no scheduler).
 */
public final class QuoteRules {
  private QuoteRules() {}

  public enum Status {
    DRAFT,
    SENT,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    CANCELLED;

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum Actor {
    OWNER,
    RESELLER
  }

  /** The status a reader sees: a sent quote is expired once {@code today} is past its last day. */
  public static Status effective(Status stored, LocalDate expiresOn, LocalDate today) {
    if (stored == null || expiresOn == null || today == null)
      throw new IllegalArgumentException("Status, expiry and today are required");
    return stored == Status.SENT && today.isAfter(expiresOn) ? Status.EXPIRED : stored;
  }

  /**
   * Legal changes: the owner sends or cancels a draft and cancels a sent quote; the reseller
   * accepts or declines a sent quote; a sent quote expires. Everything else is refused.
   */
  public static Status transition(Status current, Status target, Actor actor) {
    if (current == null || target == null || actor == null)
      throw new IllegalArgumentException("Quote status and actor are required");
    boolean allowed =
        switch (current) {
          case DRAFT ->
              actor == Actor.OWNER && (target == Status.SENT || target == Status.CANCELLED);
          case SENT ->
              target == Status.EXPIRED
                  || actor == Actor.OWNER && target == Status.CANCELLED
                  || actor == Actor.RESELLER
                      && (target == Status.ACCEPTED || target == Status.DECLINED);
          default -> false;
        };
    if (!allowed) throw new IllegalArgumentException("This quote is now " + current.wire());
    return target;
  }
}
