package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** An order. Lines, note, delivery date and total never change after placement (ADR 0012). */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  @Column(name = "reseller_id")
  public long resellerId;

  public long number;

  @Column(name = "placed_at")
  public Instant placedAt;

  @Column(name = "delivery_date")
  public LocalDate deliveryDate;

  public String status = "placed";
  public BigDecimal total;
  public String note = "";
  public boolean paid;

  @Column(name = "idempotency_key")
  public UUID idempotencyKey;

  @Column(name = "confirmed_at")
  public Instant confirmedAt;

  @Column(name = "delivered_at")
  public Instant deliveredAt;

  @Column(name = "cancelled_at")
  public Instant cancelledAt;

  @Column(name = "paid_at")
  public Instant paidAt;

  /** Scoped to the tenant, and to the reseller when one is given. */
  public static Optional<PurchaseOrder> inTenant(
      long tenant, long id, Long reseller, LockModeType lock) {
    var query =
        reseller == null
            ? find("tenantId = ?1 and id = ?2", tenant, id)
            : find("tenantId = ?1 and id = ?2 and resellerId = ?3", tenant, id, reseller);
    return query.withLock(lock).singleResultOptional();
  }
}
