package lt.printerrental.rental;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeRentalStatus(
    @NotNull RentalStatus status, @NotNull @PositiveOrZero Long version) {}
