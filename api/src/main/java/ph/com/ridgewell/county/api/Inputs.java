package ph.com.ridgewell.county.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Inputs {
  private Inputs() {}

  public record Business(
      @NotBlank @Size(max = 80) String name,
      @NotNull String cutoff,
      @NotEmpty Set<@NotNull @Min(1) @Max(7) Integer> deliveryDays) {}

  public record Product(
      @NotBlank @Size(max = 80) String name,
      @Pattern(regexp = "pack|kg|pc") @NotNull String unit,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
          BigDecimal price,
      Boolean active) {}

  public record Reseller(@NotBlank @Size(max = 80) String name, @NotBlank String mobile) {}

  public record Line(
      @NotNull @Positive Long productId,
      @NotNull
          @DecimalMin(value = "0.001", message = "Use at least 0.001")
          @DecimalMax(value = "9999", message = "Use at most 9999")
          @Digits(
              integer = 4,
              fraction = 3,
              message = "Use up to 3 decimal places and no more than 9999")
          BigDecimal quantity) {}

  public record Place(
      @NotEmpty @Size(max = 50) List<@NotNull @Valid Line> lines,
      @Size(max = 500) String note,
      @NotNull UUID idempotencyKey) {}

  public record Deliver(@NotNull Boolean paid) {}

  public record PriceTier(@NotBlank @Size(max = 80) String name, Boolean active) {}

  public record TierPrice(
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
          BigDecimal price) {}

  public record ResellerTier(@Positive Long priceTierId) {}

  /**
   * A new quote. Prices, number, status and delivery date come from the server: lines are priced
   * for the reseller as an order placed now would be.
   */
  public record Quote(
      @NotNull @Positive Long resellerId,
      @NotEmpty @Size(max = 50) List<@NotNull @Valid Line> lines,
      @Size(max = 500) String note,
      @NotNull
          @org.eclipse.microprofile.openapi.annotations.media.Schema(
              description = "Last day the reseller can accept, in the business timezone")
          LocalDate expiresOn) {}

  public record AcceptQuote(@NotNull UUID idempotencyKey) {}

  /** What was received of one product; the ordered quantity and the price come from the order. */
  public record DiscrepancyLine(
      @NotNull @Positive Long productId,
      @NotNull
          @DecimalMin(value = "0", message = "Use 0 or more")
          @DecimalMax(value = "9999", message = "Use at most 9999")
          @Digits(
              integer = 4,
              fraction = 3,
              message = "Use up to 3 decimal places and no more than 9999")
          BigDecimal receivedQuantity) {}

  public record Discrepancy(
      @NotEmpty @Size(max = 50) List<@NotNull @Valid DiscrepancyLine> lines,
      @Size(max = 500) String note,
      @NotNull UUID idempotencyKey) {}

  public record Resolve(@Size(max = 500) String note) {}
}
