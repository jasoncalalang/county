package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalTime;

/** The tenant. Every other row carries its id. */
@Entity
@Table(name = "business")
public class Business extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "owner_user_id")
  public long ownerUserId;

  public String name;
  public LocalTime cutoff;

  @Column(name = "delivery_days")
  public Integer[] deliveryDays;

  /** Next visible order number. Rolled-back placements never consume one. */
  @Column(name = "next_order_number")
  public long nextOrderNumber = 1;

  /** Next visible quote number, a sequence separate from orders. */
  @Column(name = "next_quote_number")
  public long nextQuoteNumber = 1;

  public static Business ownedBy(long userId) {
    return find("ownerUserId", userId).firstResult();
  }
}
