package lt.printerrental.rental;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePrinter(
    @NotBlank @Size(max = 100) String serialNumber,
    @NotBlank @Size(max = 100) String manufacturer,
    @NotBlank @Size(max = 120) String model,
    @NotNull Boolean colorCapable) {}
