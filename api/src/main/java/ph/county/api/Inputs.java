package ph.county.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
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
      @NotNull @Positive Long productId, @NotNull @Min(1) @Max(9999) Integer quantity) {}

  public record Place(
      @NotEmpty @Size(max = 50) List<@NotNull @Valid Line> lines,
      @Size(max = 500) String note,
      @NotNull UUID idempotencyKey) {}

  public record Deliver(@NotNull Boolean paid) {}
}
