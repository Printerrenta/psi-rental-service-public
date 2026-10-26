package lt.printerrental.repair;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "repair_requests")
public class RepairRequest {
  @Id private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID contractId;

  @Column(nullable = false, updatable = false)
  private UUID printerId;

  @Column(updatable = false, length = 255)
  private String ownerSubject;

  @Column(nullable = false, updatable = false, length = 4000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RepairStatus status;

  @Column(length = 2000)
  private String resolution;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Version private Long version;

  protected RepairRequest() {}

  RepairRequest(RepairContractAccess.ActiveContract contract, String description, Instant now) {
    this.description = description == null ? "" : description.strip();
    if (this.description.length() < 10 || this.description.length() > 4000)
      throw new IllegalArgumentException(
          "description must contain 10 to 4000 characters after trimming");
    id = UUID.randomUUID();
    contractId = contract.contractId();
    printerId = contract.printerId();
    ownerSubject = contract.ownerSubject();
    status = RepairStatus.OPEN;
    createdAt = now.truncatedTo(ChronoUnit.MICROS);
    updatedAt = createdAt;
  }

  void transitionTo(RepairStatus next, String resolution, Instant now) {
    if (!status.canTransitionTo(next))
      throw new RepairConflictException("Invalid repair transition: " + status + " -> " + next);
    var cleaned = resolution == null ? null : resolution.strip();
    if (next == RepairStatus.COMPLETED) {
      if (cleaned == null || cleaned.length() < 5 || cleaned.length() > 2000)
        throw new IllegalArgumentException(
            "Completed repairs require a resolution of 5 to 2000 characters");
    } else if (cleaned != null && !cleaned.isEmpty()) {
      throw new IllegalArgumentException("resolution is allowed only when completing a repair");
    }
    this.status = next;
    this.resolution = next == RepairStatus.COMPLETED ? cleaned : null;
    updatedAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public UUID getContractId() {
    return contractId;
  }

  public UUID getPrinterId() {
    return printerId;
  }

  public String getOwnerSubject() {
    return ownerSubject;
  }

  public String getDescription() {
    return description;
  }

  public RepairStatus getStatus() {
    return status;
  }

  public String getResolution() {
    return resolution;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Long getVersion() {
    return version;
  }
}
