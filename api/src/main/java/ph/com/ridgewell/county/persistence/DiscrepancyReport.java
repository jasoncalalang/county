package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import ph.com.ridgewell.county.domain.DiscrepancyRules;
import ph.com.ridgewell.county.domain.OrderRules;

/**
 * A short-delivery report on a delivered order. Its lines are captured at filing; resolving it is
 * the only change, and happens once. Never locked on its own: it is written under its order's
 * write lock (ADR 0015).
 */
@Entity
@Table(name = "discrepancy_report")
public class DiscrepancyReport extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  @Column(name = "order_id")
  public long orderId;

  @Column(name = "filed_by")
  public String filedBy;

  @Column(name = "filed_at")
  public Instant filedAt;

  public String status = "open";
  public String note = "";

  @Column(name = "resolution_note")
  public String resolutionNote = "";

  @Column(name = "resolved_by")
  public String resolvedBy;

  @Column(name = "resolved_at")
  public Instant resolvedAt;

  @Column(name = "shortfall_total")
  public BigDecimal shortfallTotal;

  @Column(name = "idempotency_key")
  public UUID idempotencyKey;

  public DiscrepancyRules.Status currentStatus() {
    return DiscrepancyRules.Status.valueOf(status.toUpperCase(Locale.ROOT));
  }

  public OrderRules.Actor filer() {
    return OrderRules.Actor.valueOf(filedBy.toUpperCase(Locale.ROOT));
  }

  public static Optional<DiscrepancyReport> forOrder(long tenant, long order) {
    return find("tenantId = ?1 and orderId = ?2", tenant, order).singleResultOptional();
  }

  public static Optional<DiscrepancyReport> byKey(long tenant, UUID key) {
    return find("tenantId = ?1 and idempotencyKey = ?2", tenant, key).singleResultOptional();
  }
}
