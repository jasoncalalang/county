package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/** One successful action on an order. Never updated or deleted. */
@Entity
@Table(name = "order_event")
public class OrderEvent extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  @Column(name = "order_id")
  public long orderId;

  @Column(name = "at")
  public Instant occurredAt;

  @Column(name = "from_status")
  public String fromStatus;

  @Column(name = "to_status")
  public String toStatus;

  /** The order's paid flag after the event. */
  public boolean paid;

  @Column(name = "actor_type")
  public String actorType;

  @Column(name = "actor_user_id")
  public Long actorUserId;

  @Column(name = "actor_reseller_id")
  public Long actorResellerId;
}
