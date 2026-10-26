package lt.printerrental.repair;

import java.time.Instant;
import java.util.UUID;

public record RepairResponse(
    UUID id,
    UUID contractId,
    UUID printerId,
    String description,
    RepairStatus status,
    String resolution,
    Instant createdAt,
    Instant updatedAt,
    Long version) {
  static RepairResponse from(RepairRequest request) {
    return new RepairResponse(
        request.getId(),
        request.getContractId(),
        request.getPrinterId(),
        request.getDescription(),
        request.getStatus(),
        request.getResolution(),
        request.getCreatedAt(),
        request.getUpdatedAt(),
        request.getVersion());
  }
}
