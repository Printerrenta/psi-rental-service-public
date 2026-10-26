package lt.printerrental.rental;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "rental_requests")
public class RentalRequest {
  @Id private UUID id;
  @Column private UUID resourceId;
  private UUID printerId;

  @Column(length = 255)
  private String ownerSubject;

  private Integer estimatedMonthlyPages;
  private Boolean colorPrintingRequired;

  @Column(nullable = false, length = 120)
  private String customerName;

  @Column(nullable = false, length = 254)
  private String customerEmail;

  @Column(nullable = false)
  private LocalDate startDate;

  @Column(nullable = false)
  private LocalDate endDate;

  @Column(length = 2000)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RentalStatus status;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @Version private Long version;

  protected RentalRequest() {}

  RentalRequest(CreateRentalRequest input, Instant now) {
    id = UUID.randomUUID();
    estimatedMonthlyPages = input.estimatedMonthlyPages();
    colorPrintingRequired = input.colorPrintingRequired();
    customerName = input.customerName().strip();
    customerEmail = input.customerEmail().strip();
    startDate = input.startDate();
    endDate = input.endDate();
    notes = input.notes() == null ? null : input.notes().strip();
    status = RentalStatus.PENDING;
    createdAt = now.truncatedTo(ChronoUnit.MICROS);
    updatedAt = createdAt;
  }

  void transitionTo(RentalStatus next, Instant now) {
    if (!status.canTransitionTo(next)) {
      throw new RentalConflictException("Cannot change status from " + status + " to " + next);
    }
    status = next;
    updatedAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  public String getOwnerSubject() {
    return ownerSubject;
  }

  void setOwnerSubject(String subject) {
    ownerSubject = subject;
  }

  public UUID getId() {
    return id;
  }

  public UUID getResourceId() {
    return resourceId;
  }

  public UUID getPrinterId() {
    return printerId;
  }

  void assignPrinter(UUID id, Instant now) {
    printerId = id;
    updatedAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  public Integer getEstimatedMonthlyPages() {
    return estimatedMonthlyPages;
  }

  public Boolean getColorPrintingRequired() {
    return colorPrintingRequired;
  }

  public String getCustomerName() {
    return customerName;
  }

  public String getCustomerEmail() {
    return customerEmail;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public String getNotes() {
    return notes;
  }

  public RentalStatus getStatus() {
    return status;
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
