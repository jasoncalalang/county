package ph.com.ridgewell.county.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** A quote line with the product's name, unit and price copied when the quote was created. */
@Entity
@Table(name = "quote_line")
public class QuoteLine extends PanacheEntityBase {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "tenant_id")
  public long tenantId;

  @Column(name = "quote_id")
  public long quoteId;

  @Column(name = "product_id")
  public long productId;

  @Column(name = "product_name")
  public String productName;

  public String unit;
  public int quantity;
  public BigDecimal price;
  public BigDecimal total;
}
