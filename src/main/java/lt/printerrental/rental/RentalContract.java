package lt.printerrental.rental;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "rental_contracts")
public class RentalContract {
  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private UUID rentalRequestId;

  @Column(nullable = false)
  private UUID printerId;

  @Column(nullable = false, length = 120)
  private String customerName;

  @Column(nullable = false, length = 254)
  private String customerEmail;

  @Column(nullable = false)
  private LocalDate startDate;

  @Column(nullable = false)
  private LocalDate endDate;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal monthlyFee;

  @Column(nullable = false)
  private Integer includedMonoPages;

  @Column(nullable = false)
  private Integer includedColorPages;

  @Column(nullable = false, precision = 10, scale = 4)
  private BigDecimal monoPagePrice;

  @Column(nullable = false, precision = 10, scale = 4)
  private BigDecimal colorPagePrice;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false, length = 20)
  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  private ContractStatus status;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;
  private Long initialMonoReading;
  private Long initialColorReading;
  private LocalDate initialReadingDate;
  private Instant activatedAt;
  private Long finalMonoReading;
  private Long finalColorReading;
  private LocalDate finalReadingDate;
  private Instant completedAt;

  protected RentalContract() {}

  RentalContract(RentalRequest request, CreateContract input, Instant now) {
    id = UUID.randomUUID();
    rentalRequestId = request.getId();
    printerId = request.getPrinterId();
    customerName = request.getCustomerName();
    customerEmail = request.getCustomerEmail();
    startDate = request.getStartDate();
    endDate = request.getEndDate();
    monthlyFee = input.monthlyFee().setScale(2);
    includedMonoPages = input.includedMonoPages();
    includedColorPages = input.includedColorPages();
    monoPagePrice = input.monoPagePrice().setScale(4);
    colorPagePrice = input.colorPagePrice().setScale(4);
    currency = "EUR";
    status = ContractStatus.DRAFT;
    createdAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public UUID getRentalRequestId() {
    return rentalRequestId;
  }

  public UUID getPrinterId() {
    return printerId;
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

  public BigDecimal getMonthlyFee() {
    return monthlyFee;
  }

  public Integer getIncludedMonoPages() {
    return includedMonoPages;
  }

  public Integer getIncludedColorPages() {
    return includedColorPages;
  }

  public BigDecimal getMonoPagePrice() {
    return monoPagePrice;
  }

  public BigDecimal getColorPagePrice() {
    return colorPagePrice;
  }

  public String getCurrency() {
    return currency;
  }

  public ContractStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Long getVersion() {
    return version;
  }

  public Long getInitialMonoReading() {
    return initialMonoReading;
  }

  public Long getInitialColorReading() {
    return initialColorReading;
  }

  public LocalDate getInitialReadingDate() {
    return initialReadingDate;
  }

  public Instant getActivatedAt() {
    return activatedAt;
  }

  public Long getFinalMonoReading() {
    return finalMonoReading;
  }

  public Long getFinalColorReading() {
    return finalColorReading;
  }

  public LocalDate getFinalReadingDate() {
    return finalReadingDate;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Long getTotalMonoPages() {
    return finalMonoReading == null ? null : finalMonoReading - initialMonoReading;
  }

  public Long getTotalColorPages() {
    return finalColorReading == null ? null : finalColorReading - initialColorReading;
  }

  void complete(CompleteContract input, Instant now) {
    if (status != ContractStatus.ACTIVE) {
      throw new RentalConflictException("Only an ACTIVE contract can be completed");
    }
    if (input.finalMonoReading() < initialMonoReading
        || input.finalColorReading() < initialColorReading) {
      throw new IllegalArgumentException("Final readings must be on or above the initial readings");
    }
    if (input.finalReadingDate().isBefore(initialReadingDate)
        || input.finalReadingDate().isAfter(endDate)) {
      throw new IllegalArgumentException("Final reading date must be within the rental period");
    }
    finalMonoReading = input.finalMonoReading();
    finalColorReading = input.finalColorReading();
    finalReadingDate = input.finalReadingDate();
    completedAt = now.truncatedTo(ChronoUnit.MICROS);
    status = ContractStatus.COMPLETED;
  }

  void activate(ActivateContract input, Instant now) {
    if (status != ContractStatus.DRAFT) {
      throw new RentalConflictException("Only a DRAFT contract can be activated");
    }
    initialMonoReading = input.initialMonoReading();
    initialColorReading = input.initialColorReading();
    initialReadingDate = input.initialReadingDate();
    activatedAt = now.truncatedTo(ChronoUnit.MICROS);
    status = ContractStatus.ACTIVE;
  }
}
