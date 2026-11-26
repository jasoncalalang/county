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
      @Schema(format = "date-time", nullable = true) String lastOrderAt,
      @Schema(nullable = true) Long priceTierId) {}

  @Schema(name = "PriceTierView")
  public record PriceTier(long id, String name, boolean active) {}

  @Schema(name = "TierProductPriceView")
  public record TierProductPrice(
      long productId,
      String productName,
      @Schema(enumeration = {"pack", "kg", "pc"}) String unit,
      BigDecimal basePrice,
      @Schema(nullable = true) BigDecimal tierPrice) {}

  @Schema(name = "TierPricesView")
  public record TierPrices(long id, String name, boolean active, List<TierProductPrice> products) {}

  @Schema(name = "OrderLineView")
  public record OrderLine(
      long productId,
      String productName,
      String unit,
      int quantity,
      BigDecimal price,
      BigDecimal total) {}

  @Schema(name = "OrderItemView", description = "One line of an order, as the order list shows it")
  public record OrderItem(
      String productName, @Schema(enumeration = {"pack", "kg", "pc"}) String unit, int quantity) {}

  @Schema(name = "OrderSummaryView")
  public record OrderSummary(
      long id,
      long number,
      long resellerId,
      String resellerName,
      @Schema(format = "date-time") String placedAt,
      @Schema(format = "date") String deliveryDate,
      @Schema(enumeration = {"placed", "confirmed", "delivered", "cancelled"}) String status,
      BigDecimal total,
      boolean paid,
      String note,
      @Schema(description = "Lines in the order they were entered") List<OrderItem> items) {}

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
      String resellerName,
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

  @Schema(name = "SettingsPreviewView")
  public record SettingsPreview(
      @Schema(
              format = "date",
              description = "Delivery date of an order placed now with these settings")
          String deliveryDate,
      @Schema(description = "IANA timezone the cut-off is compared in") String timeZone) {}

  @Schema(name = "ProductionLineView")
  public record ProductionLine(
      long productId, String productName, String unit, long quantity, long orderCount) {}

  @Schema(name = "ProductionRunView")
  public record ProductionRun(
      @Schema(format = "date") String date, List<ProductionLine> lines, long unconfirmedCount) {}

  @Schema(name = "QuoteLineView")
  public record QuoteLine(
      long productId,
      String productName,
      @Schema(enumeration = {"pack", "kg", "pc"}) String unit,
      int quantity,
      BigDecimal price,
      BigDecimal total) {}

  @Schema(name = "QuoteSummaryView")
  public record QuoteSummary(
      long id,
      long number,
      long resellerId,
      String resellerName,
      @Schema(format = "date-time") String createdAt,
      @Schema(format = "date") String deliveryDate,
      @Schema(format = "date") String expiresOn,
      @Schema(enumeration = {"draft", "sent", "accepted", "declined", "expired", "cancelled"})
          String status,
      BigDecimal total) {}

  @Schema(name = "QuoteView")
  public record Quote(
      long id,
      long resellerId,
      String resellerName,
      long number,
      @Schema(format = "date-time") String createdAt,
      @Schema(
              format = "date",
              description =
                  "Delivery date of an order placed when the quote was created. Accepting places"
                      + " an order whose delivery date is computed at acceptance.")
          String deliveryDate,
      @Schema(format = "date", description = "Last day the reseller can accept") String expiresOn,
      @Schema(
              enumeration = {"draft", "sent", "accepted", "declined", "expired", "cancelled"},
              description = "A sent quote reads as expired once its last day has passed")
          String status,
      BigDecimal total,
      String note,
      @Schema(nullable = true) Long acceptedOrderId,
      @Schema(format = "date-time", nullable = true) String sentAt,
      @Schema(format = "date-time", nullable = true) String acceptedAt,
      @Schema(format = "date-time", nullable = true) String declinedAt,
      @Schema(format = "date-time", nullable = true) String cancelledAt,
      List<QuoteLine> lines) {}
}
