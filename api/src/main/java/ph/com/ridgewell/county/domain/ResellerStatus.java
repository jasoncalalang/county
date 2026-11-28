package ph.com.ridgewell.county.domain;

import java.util.Locale;

/** A reseller's status as the owner sees it: invited until the first order, then active. */
public enum ResellerStatus {
  INVITED,
  ACTIVE,
  BLOCKED;

  /** The lowercase name the column and the API use. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
