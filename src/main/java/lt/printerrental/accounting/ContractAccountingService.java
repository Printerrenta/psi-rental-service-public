package lt.printerrental.accounting;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.auth.CurrentUser;
import lt.printerrental.auth.RentalAccessPolicy;
import lt.printerrental.rental.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractAccountingService {
  private final ContractService contracts;
  private final ContractRepository contractRows;
  private final RentalRequestRepository requests;
  private final AccountingRepository repository;
  private final AccountingSnapshotPublisher snapshots;
  private final CurrentUser user;
  private final RentalAccessPolicy access;
  private final Clock clock;

  public ContractAccountingService(
      ContractService contracts,
      ContractRepository contractRows,
      RentalRequestRepository requests,
      AccountingRepository repository,
      AccountingSnapshotPublisher snapshots,
      CurrentUser user,
      RentalAccessPolicy access,
      Clock clock) {
    this.contracts = contracts;
    this.contractRows = contractRows;
    this.requests = requests;
    this.repository = repository;
    this.snapshots = snapshots;
    this.user = user;
    this.access = access;
    this.clock = clock;
  }

  public record Initialize(
      boolean a3Capable, List<Long> baseline, BigDecimal vatPercent, Integer paymentTermDays) {}

  public record Change(
      long revision,
      LocalDate effectiveFrom,
      BigDecimal monthlyFee,
      BigDecimal monoPrice,
      BigDecimal colorPrice,
      BigDecimal vatPercent,
      Integer paymentTermDays) {}

  @Transactional(readOnly = true)
  public AccountingSnapshot get(UUID id) {
    var c = contracts.get(id);
    return snapshots.snapshot(id, owner(c));
  }

  @Transactional
  public AccountingSnapshot initialize(UUID id, Initialize input) {
    access.requireStaff();
    lock(id);
    var c = contracts.get(id);
    if (repository.find(id).isPresent())
      throw new RentalConflictException("Accounting already initialized");
    if (c.getStatus() == ContractStatus.COMPLETED)
      throw new RentalConflictException("Initialize accounting before completion");
    if (c.getIncludedMonoPages() != 0 || c.getIncludedColorPages() != 0)
      throw new RentalConflictException("This flow requires zero included-page allowances");
    if (input.baseline() == null
        || input.baseline().size() != 4
        || input.baseline().stream().anyMatch(n -> n == null || n < 0))
      throw new IllegalArgumentException("Four nonnegative initial counters are required");
    validate(
        c.getMonthlyFee(),
        c.getMonoPagePrice(),
        c.getColorPagePrice(),
        input.vatPercent(),
        input.paymentTermDays());
    repository.initialize(id, input.a3Capable(), input.baseline());
    var s = snapshots.snapshot(id, owner(c));
    if (!s.colorCapable() && (input.baseline().get(1) != 0 || input.baseline().get(3) != 0))
      throw new IllegalArgumentException("Monochrome printer requires zero color counters");
    if (!s.a3Capable() && (input.baseline().get(2) != 0 || input.baseline().get(3) != 0))
      throw new IllegalArgumentException("A4 printer requires zero A3 counters");
    repository.add(
        id,
        new AccountingSnapshot.Terms(
            UUID.randomUUID(),
            c.getStartDate(),
            c.getMonthlyFee(),
            c.getMonoPagePrice(),
            c.getColorPagePrice(),
            input.vatPercent(),
            input.paymentTermDays()),
        user.subject());
    snapshots.changed(id, owner(c));
    return get(id);
  }

  @Transactional
  public AccountingSnapshot change(UUID id, Change input) {
    access.requireStaff();
    lock(id);
    var c = contracts.get(id);
    var s = snapshots.snapshot(id, owner(c));
    if (s.revision() != input.revision())
      throw new RentalConflictException("Accounting changed; use current revision");
    if (c.getStatus() == ContractStatus.COMPLETED)
      throw new RentalConflictException("Completed contract conditions cannot change");
    var today = LocalDate.now(clock.withZone(ZoneId.of("Europe/Vilnius")));
    if (input.effectiveFrom() == null
        || input.effectiveFrom().isBefore(today)
        || !input.effectiveFrom().isAfter(c.getStartDate())
        || input.effectiveFrom().isAfter(c.getEndDate()))
      throw new IllegalArgumentException(
          "New terms must start today or later within the contract after its start");
    if (s.terms().stream().anyMatch(t -> !t.effectiveFrom().isBefore(input.effectiveFrom())))
      throw new RentalConflictException("Append terms after existing scheduled terms");
    validate(
        input.monthlyFee(),
        input.monoPrice(),
        input.colorPrice(),
        input.vatPercent(),
        input.paymentTermDays());
    if (!s.colorCapable() && input.colorPrice().signum() != 0)
      throw new IllegalArgumentException("Monochrome printer requires zero color price");
    repository.add(
        id,
        new AccountingSnapshot.Terms(
            UUID.randomUUID(),
            input.effectiveFrom(),
            input.monthlyFee(),
            input.monoPrice(),
            input.colorPrice(),
            input.vatPercent(),
            input.paymentTermDays()),
        user.subject());
    snapshots.changed(id, owner(c));
    return get(id);
  }

  private String owner(RentalContract c) {
    var owner =
        requests
            .findById(c.getRentalRequestId())
            .orElseThrow(() -> new ContractNotFoundException(c.getId()))
            .getOwnerSubject();
    if (owner == null || owner.isBlank())
      throw new RentalConflictException("A verified customer owner is required for accounting");
    return owner;
  }

  private void lock(UUID id) {
    contractRows.lockForAccounting(id).orElseThrow(() -> new ContractNotFoundException(id));
  }

  private void validate(
      BigDecimal fee, BigDecimal mono, BigDecimal color, BigDecimal vat, Integer days) {
    if (fee == null
        || mono == null
        || color == null
        || vat == null
        || days == null
        || fee.signum() < 0
        || mono.signum() < 0
        || color.signum() < 0
        || vat.signum() < 0
        || vat.compareTo(new BigDecimal("100")) > 0
        || fee.precision() - fee.scale() > 10
        || mono.precision() - mono.scale() > 6
        || color.precision() - color.scale() > 6
        || fee.stripTrailingZeros().scale() > 2
        || mono.stripTrailingZeros().scale() > 4
        || color.stripTrailingZeros().scale() > 4
        || vat.stripTrailingZeros().scale() > 4
        || days < 0
        || days > 365)
      throw new IllegalArgumentException("Invalid EUR amounts, VAT or payment term");
  }
}
