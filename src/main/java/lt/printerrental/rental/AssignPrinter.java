package lt.printerrental.rental;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record AssignPrinter(@NotNull UUID printerId, @NotNull @PositiveOrZero Long version) {}
