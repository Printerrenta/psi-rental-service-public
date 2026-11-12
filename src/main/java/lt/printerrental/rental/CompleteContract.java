package lt.printerrental.rental;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.List;
import jakarta.validation.constraints.Size;

public record CompleteContract(
    @NotNull @PositiveOrZero Long version,
    @NotNull @PositiveOrZero Long finalMonoReading,
    @NotNull @PositiveOrZero Long finalColorReading,
    @NotNull LocalDate finalReadingDate,
    @Size(min = 4, max = 4) List<@NotNull @PositiveOrZero Long> finalCounters) {
  public CompleteContract(Long version, Long finalMonoReading, Long finalColorReading,
      LocalDate finalReadingDate) {
    this(version, finalMonoReading, finalColorReading, finalReadingDate, null);
  }
}
