package lt.printerrental.rental;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateContract(
    @NotNull UUID rentalRequestId,
    @NotNull @PositiveOrZero Long requestVersion,
    @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal monthlyFee,
    @NotNull @PositiveOrZero Integer includedMonoPages,
    @NotNull @PositiveOrZero Integer includedColorPages,
    @NotNull @DecimalMin("0") @Digits(integer = 6, fraction = 4) BigDecimal monoPagePrice,
    @NotNull @DecimalMin("0") @Digits(integer = 6, fraction = 4) BigDecimal colorPagePrice,
    UUID printerId) {
  /** Compatibility for existing callers; new clients select the device on the contract. */
  public CreateContract(UUID rentalRequestId, Long requestVersion, BigDecimal monthlyFee,
      Integer includedMonoPages, Integer includedColorPages, BigDecimal monoPagePrice,
      BigDecimal colorPagePrice) {
    this(rentalRequestId, requestVersion, monthlyFee, includedMonoPages, includedColorPages,
        monoPagePrice, colorPagePrice, null);
  }
}
