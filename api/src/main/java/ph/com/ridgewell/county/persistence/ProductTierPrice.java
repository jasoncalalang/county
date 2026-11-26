package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * One product's price under one tier. Guarded by the product's lock: the owner takes the product's
 * exclusive lock before writing a tier price, so a placement reading it (under the product's share
 * lock) snapshots a committed value, keeping the reseller -> business -> product -> order order.
 */
@Entity
@Table(name = "product_tier_price")
public class ProductTierPrice extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  @Column(name = "product_id")
  public long productId;

  @Column(name = "price_tier_id")
  public long priceTierId;

  public BigDecimal price;

  public static Optional<ProductTierPrice> of(long tenant, long productId, long tierId) {
    return find(
            "tenantId = ?1 and productId = ?2 and priceTierId = ?3", tenant, productId, tierId)
        .singleResultOptional();
  }
}
