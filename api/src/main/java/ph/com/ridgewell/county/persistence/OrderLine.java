package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** A line with the product's name, unit and price copied at placement. */
@Entity
@Table(name = "order_line")
public class OrderLine extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  @Column(name = "order_id")
  public long orderId;

  @Column(name = "product_id")
  public long productId;

  @Column(name = "product_name")
  public String productName;

  public String unit;
  public BigDecimal quantity;
  public BigDecimal price;
  public BigDecimal total;
}
