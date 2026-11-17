package ph.com.ridgewell.county.domain;

/** Status changes only; marking a delivered order paid is a separate operation. */
public final class OrderRules {
  private OrderRules() {}

  public enum Status {
    PLACED,
    CONFIRMED,
    DELIVERED,
    CANCELLED
  }

  public enum Actor {
    OWNER,
    RESELLER
  }

  public static Status transition(Status current, Status target, Actor actor) {
    if (current == null || target == null || actor == null) {
      throw new IllegalArgumentException("Order status and actor are required");
    }
    boolean allowed =
        current == Status.PLACED && target == Status.CANCELLED
            || actor == Actor.OWNER
                && (current == Status.PLACED && target == Status.CONFIRMED
                    || current == Status.CONFIRMED
                        && (target == Status.DELIVERED || target == Status.CANCELLED));
    if (!allowed)
      throw new IllegalArgumentException(
          "This order is now " + current.name().toLowerCase(java.util.Locale.ROOT));
    return target;
  }
}
