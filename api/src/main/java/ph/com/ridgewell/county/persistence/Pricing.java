package ph.com.ridgewell.county.persistence;

import static jakarta.persistence.LockModeType.PESSIMISTIC_READ;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ph.com.ridgewell.county.api.Inputs;
import ph.com.ridgewell.county.api.Problem;
import ph.com.ridgewell.county.domain.Money;
import ph.com.ridgewell.county.domain.Unit;

/** Prices submitted lines for a reseller; shared by placing an order and creating a quote. */
@ApplicationScoped
public class Pricing {
  /** A submitted line priced for a reseller: the product as read under its lock. */
  record PricedLine(Product product, BigDecimal quantity, BigDecimal price, BigDecimal total) {}

  record Priced(List<PricedLine> lines, BigDecimal total) {}

  /**
   * Prices lines for a reseller at today's prices: the reseller's tier price where one is set,
   * else the product's base price. Share-locks each product in ascending id order, which guards
   * its tier prices too, so call it after the reseller and business locks (ADR 0013). Refuses a
   * repeated or inactive product and a fraction of a unit sold whole; a field error names the
   * line as submitted. Each line is rounded half up to centavos and the total is their sum
   * (ADR 0017).
   */
  Priced price(Reseller reseller, List<Inputs.Line> submitted) {
    Map<Long, Integer> firstIndex = new HashMap<>();
    for (int i = 0; i < submitted.size(); i++)
      if (firstIndex.putIfAbsent(submitted.get(i).productId(), i) != null)
        throw Problem.field(
            "lines[" + i + "].productId", "This product is already on another line");
    List<PricedLine> lines = new ArrayList<>();
    BigDecimal total = new BigDecimal("0.00");
    // Ascending product ids: a stable lock order avoids deadlocks with product edits.
    for (var line :
        submitted.stream().sorted(Comparator.comparing(Inputs.Line::productId)).toList()) {
      String field = "lines[" + firstIndex.get(line.productId()) + "]";
      var product =
          Product.inTenant(reseller.tenantId, line.productId(), PESSIMISTIC_READ)
              .orElseThrow(() -> new Problem(404, "Not found"));
      if (!product.active)
        throw Problem.field(field + ".productId", product.name + " is no longer available");
      if (!Unit.of(product.unit).allows(line.quantity()))
        throw Problem.field(
            field + ".quantity",
            product.name + " is sold by the " + product.unit + ", so enter a whole number");
      // The product's read lock guards the tier price read.
      BigDecimal unitPrice =
          reseller.priceTierId == null
              ? product.price
              : ProductTierPrice.of(reseller.tenantId, product.id, reseller.priceTierId)
                  .map(t -> t.price)
                  .orElse(product.price);
      final BigDecimal lineTotal;
      try {
        lineTotal = Money.line(unitPrice, line.quantity());
      } catch (IllegalArgumentException e) {
        throw Problem.field(field + ".quantity", e.getMessage());
      }
      total = total.add(lineTotal);
      if (total.compareTo(Money.MAXIMUM) > 0)
        throw Problem.field("lines", "Order total is too large");
      lines.add(new PricedLine(product, line.quantity(), unitPrice, lineTotal));
    }
    return new Priced(lines, total);
  }
}
