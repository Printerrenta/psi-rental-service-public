package lt.printerrental.repair;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record CreateRepairRequest(
    @NotNull UUID contractId, @NotBlank @Size(min = 10, max = 4000) String description) {}
