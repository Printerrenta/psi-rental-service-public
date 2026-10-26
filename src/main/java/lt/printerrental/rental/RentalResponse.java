package lt.printerrental.rental;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RentalResponse(
    UUID id,
    UUID resourceId,
    UUID printerId,
    String customerName,
    String customerEmail,
    Integer estimatedMonthlyPages,
    Boolean colorPrintingRequired,
    LocalDate startDate,
    LocalDate endDate,
    String notes,
    RentalStatus status,
    Instant createdAt,
    Instant updatedAt,
    Long version) {
  static RentalResponse from(RentalRequest entity) {
    return new RentalResponse(
        entity.getId(),
        entity.getResourceId(),
        entity.getPrinterId(),
        entity.getCustomerName(),
        entity.getCustomerEmail(),
        entity.getEstimatedMonthlyPages(),
        entity.getColorPrintingRequired(),
        entity.getStartDate(),
        entity.getEndDate(),
        entity.getNotes(),
        entity.getStatus(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getVersion());
  }
}
