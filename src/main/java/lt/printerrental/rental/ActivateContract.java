package lt.printerrental.rental;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;

public record ActivateContract(
    @NotNull @PositiveOrZero Long version,
    @NotNull @PositiveOrZero Long initialMonoReading,
    @NotNull @PositiveOrZero Long initialColorReading,
    @NotNull LocalDate initialReadingDate) {}
