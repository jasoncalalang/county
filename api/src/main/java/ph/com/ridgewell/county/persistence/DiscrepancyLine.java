package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** One product of a report, with the order line's name, unit, quantity and price copied. */
@Entity
@Table(name = "discrepancy_line")
public class DiscrepancyLine extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  @Column(name = "report_id")
  public long reportId;

  @Column(name = "product_id")
  public long productId;

  @Column(name = "product_name")
  public String productName;

  public String unit;

  @Column(name = "ordered_quantity")
  public BigDecimal orderedQuantity;

  @Column(name = "received_quantity")
  public BigDecimal receivedQuantity;

  public BigDecimal price;

  @Column(name = "shortfall_total")
  public BigDecimal shortfallTotal;
}
