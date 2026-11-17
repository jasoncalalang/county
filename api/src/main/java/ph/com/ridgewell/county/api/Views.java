package ph.com.ridgewell.county.api;

import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Explicit wire contracts: entities are never serialized by resource methods. */
public final class Views {
  private Views() {}

  @Schema(name = "UserView")
  public record User(
      long id,
      @Schema(nullable = true) String displayName,
      @Schema(nullable = true) String email) {}

  @Schema(name = "MeView")
  public record Me(String subject, User user, @Schema(nullable = true) Business tenant) {}

  @Schema(name = "BusinessView")
  public record Business(
      long id, String name, @Schema(format = "time") String cutoff, List<Integer> deliveryDays) {}

  @Schema(name = "ProductView")
  public record Product(
      long id,
      String name,
      @Schema(enumeration = {"pack", "kg", "pc"}) String unit,
      BigDecimal price,
      boolean active) {}

  @Schema(name = "CatalogProductView")
  public record CatalogProduct(
      long id,
      String name,
      @Schema(enumeration = {"pack", "kg", "pc"}) String unit,
      BigDecimal price) {}

  @Schema(name = "ResellerView")
  public record Reseller(
      long id,
      String name,
      String mobile,
      @Schema(enumeration = {"invited", "active", "blocked"}) String status,
      String token,
      String orderLink,
      @Schema(format = "date-time", nullable = true) String lastOrderAt) {}

  @Schema(name = "OrderLineView")
  public record OrderLine(
      long productId,
      String productName,
      String unit,
      int quantity,
      BigDecimal price,
      BigDecimal total) {}

  @Schema(name = "OrderSummaryView")
  public record OrderSummary(
      long id,
      long number,
      long resellerId,
      @Schema(format = "date-time") String placedAt,
      @Schema(format = "date") String deliveryDate,
      @Schema(enumeration = {"placed", "confirmed", "delivered", "cancelled"}) String status,
      BigDecimal total,
      boolean paid,
      String note) {}

  @Schema(name = "RecentOrderView")
  public record RecentOrder(
      long id,
      long number,
      @Schema(format = "date") String deliveryDate,
      BigDecimal total,
      @Schema(enumeration = {"placed", "confirmed", "delivered", "cancelled"}) String status,
      @Schema(format = "date-time") String placedAt) {}

  @Schema(name = "OrderView")
  public record Order(
      long id,
      long resellerId,
      long number,
      @Schema(format = "date-time") String placedAt,
      @Schema(format = "date") String deliveryDate,
      @Schema(enumeration = {"placed", "confirmed", "delivered", "cancelled"}) String status,
      BigDecimal total,
      String note,
      boolean paid,
      @Schema(format = "date-time", nullable = true) String confirmedAt,
      @Schema(format = "date-time", nullable = true) String deliveredAt,
      @Schema(format = "date-time", nullable = true) String cancelledAt,
      @Schema(format = "date-time", nullable = true) String paidAt,
      List<OrderLine> lines) {}

  @Schema(name = "CatalogView")
  public record Catalog(
      String businessName,
      String resellerName,
      @Schema(format = "date") String deliveryDate,
      @Schema(format = "time") String cutoff,
      List<CatalogProduct> products,
      List<RecentOrder> recentOrders) {}

  @Schema(name = "ProductionLineView")
  public record ProductionLine(
      long productId, String productName, String unit, long quantity, long orderCount) {}

  @Schema(name = "ProductionRunView")
  public record ProductionRun(
      @Schema(format = "date") String date, List<ProductionLine> lines, long unconfirmedCount) {}
}
