package lt.printerrental.rental;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateRentalRequest(
    @NotBlank @Size(max = 120) String customerName,
    @NotBlank @Email @Size(max = 254) String customerEmail,
    @NotNull @Positive Integer estimatedMonthlyPages,
    @NotNull Boolean colorPrintingRequired,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @Size(max = 2000) String notes) {}
