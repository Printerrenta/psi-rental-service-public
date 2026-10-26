package lt.printerrental.repair;

import jakarta.validation.constraints.*;

public record ChangeRepairStatus(
    @NotNull RepairStatus status,
    @NotNull @PositiveOrZero Long version,
    @Size(max = 2000) String resolution) {}
