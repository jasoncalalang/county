package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
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

  public String status = "invited";

  @Column(name = "has_ordered")
  public boolean hasOrdered;

  public static Optional<Reseller> inTenant(long tenant, long id, LockModeType lock) {
    return find("tenantId = ?1 and id = ?2", tenant, id).withLock(lock).singleResultOptional();
  }

  /** The reseller behind an order link, unless blocked. */
  public static Optional<Reseller> byToken(String token, LockModeType lock) {
    return find("token = ?1 and status <> 'blocked'", token).withLock(lock).singleResultOptional();
  }
}
