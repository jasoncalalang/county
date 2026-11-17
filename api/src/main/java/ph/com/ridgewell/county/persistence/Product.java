package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Optional;

@Entity
@Table(name = "product")
public class Product extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  public String name;
  public String unit;
  public BigDecimal price;
  public boolean active = true;

  public static Optional<Product> inTenant(long tenant, long id, LockModeType lock) {
    return find("tenantId = ?1 and id = ?2", tenant, id).withLock(lock).singleResultOptional();
  }
}
