package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "reseller")
public class Reseller extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  public String name;
  public String mobile;

  /** Bearer secret in the reseller's order link. Never log it. */
  public String token;

  /** When the link stops working (ADR 0018). */
  @Column(name = "token_expires_at")
  public Instant tokenExpiresAt;

  public String status = "invited";

  @Column(name = "has_ordered")
  public boolean hasOrdered;

  /** The reseller's price tier, or null for the product's base price. */
  @Column(name = "price_tier_id")
  public Long priceTierId;

  public static Optional<Reseller> inTenant(long tenant, long id, LockModeType lock) {
    return find("tenantId = ?1 and id = ?2", tenant, id).withLock(lock).singleResultOptional();
  }

  /** The reseller behind an order link, unless blocked or the link has expired at {@code now}. */
  public static Optional<Reseller> byToken(String token, Instant now, LockModeType lock) {
    return find("token = ?1 and status <> 'blocked' and tokenExpiresAt > ?2", token, now)
        .withLock(lock)
        .singleResultOptional();
  }
}
