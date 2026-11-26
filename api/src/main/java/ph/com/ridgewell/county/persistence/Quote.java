package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * A quote: prices offered to one reseller. Lines, total and delivery estimate never change; only
 * the status and its timestamps do. Accepting it places an order at the quoted prices.
 */
@Entity
@Table(name = "quote")
public class Quote extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  @Column(name = "reseller_id")
  public long resellerId;

  public long number;

  @Column(name = "created_at")
  public Instant createdAt;

  /** The last day the reseller can accept, in the business timezone. */
  @Column(name = "expires_on")
  public LocalDate expiresOn;

  /** The delivery date an order placed when the quote was created would have had. */
  @Column(name = "delivery_date")
  public LocalDate deliveryDate;

  /** As stored; read it through {@code Quotes.status} to see expiry. */
  public String status = "draft";

  public BigDecimal total;
  public String note = "";

  @Column(name = "accepted_order_id")
  public Long acceptedOrderId;

  @Column(name = "sent_at")
  public Instant sentAt;

  @Column(name = "accepted_at")
  public Instant acceptedAt;

  @Column(name = "declined_at")
  public Instant declinedAt;

  @Column(name = "cancelled_at")
  public Instant cancelledAt;

  /** Scoped to the tenant, and to the reseller when one is given. */
  public static Optional<Quote> inTenant(long tenant, long id, Long reseller, LockModeType lock) {
    var query =
        reseller == null
            ? find("tenantId = ?1 and id = ?2", tenant, id)
            : find("tenantId = ?1 and id = ?2 and resellerId = ?3", tenant, id, reseller);
    return query.withLock(lock).singleResultOptional();
  }
}
