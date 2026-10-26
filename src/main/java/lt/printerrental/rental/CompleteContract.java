package lt.printerrental.rental;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;

public record CompleteContract(
    @NotNull @PositiveOrZero Long version,
    @NotNull @PositiveOrZero Long finalMonoReading,
    @NotNull @PositiveOrZero Long finalColorReading,
    @NotNull LocalDate finalReadingDate) {}
