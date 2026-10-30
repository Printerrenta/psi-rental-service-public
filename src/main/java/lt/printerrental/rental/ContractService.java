package lt.printerrental.rental;

import java.time.Clock;
import java.util.UUID;
import lt.printerrental.auth.CurrentUser;
import lt.printerrental.auth.RentalAccessPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContractService {
  private final ContractAccountingObserver accounting;
  private final ContractRepository contracts;
  private final RentalRequestRepository requests;
  private final PrinterRepository printers;
  private final Clock clock;
  private final lt.printerrental.events.EventRecorder events;
  private final CurrentUser currentUser;
  private final RentalAccessPolicy access;

  public ContractService(
      ContractRepository contracts,
      RentalRequestRepository requests,
      PrinterRepository printers,
      Clock clock,
      CurrentUser currentUser,
      RentalAccessPolicy access,
      lt.printerrental.events.EventRecorder events,
      ContractAccountingObserver accounting) {
    this.accounting = accounting;
    this.contracts = contracts;
    this.requests = requests;
    this.printers = printers;
    this.clock = clock;
    this.events = events;
    this.currentUser = currentUser;
    this.access = access;
  }

  @Transactional
  public RentalContract create(CreateContract input) {
    access.requireStaff();
    var original = requests.findById(input.rentalRequestId())
        .orElseThrow(() -> new RentalNotFoundException(input.rentalRequestId()));
    var selectedPrinter = input.printerId() != null ? input.printerId() : original.getPrinterId();
    if (selectedPrinter == null) throw new RentalConflictException("Choose a printer when creating the contract");
    // All device operations lock printer before request, including concurrent contract creation.
    var printer = printers.lockById(selectedPrinter)
        .orElseThrow(() -> new PrinterNotFoundException(selectedPrinter));
    var request =
        requests
            .lockById(input.rentalRequestId())
            .orElseThrow(() -> new RentalNotFoundException(input.rentalRequestId()));
    if (!input.requestVersion().equals(request.getVersion())) {
      throw new RentalConflictException("Request changed; reload it and use its current version");
    }
    if (request.getStatus() != RentalStatus.APPROVED) {
      throw new RentalConflictException(
          "Contract requires an APPROVED request");
    }
    if (contracts.existsByRentalRequestId(request.getId())) {
      throw new RentalConflictException("A contract already exists for this request");
    }
    if (input.printerId() == null && !selectedPrinter.equals(request.getPrinterId()))
      throw new RentalConflictException("Request changed; reload it and choose the contract printer");
    if (Boolean.TRUE.equals(request.getColorPrintingRequired()) && !printer.isColorCapable())
      throw new IllegalArgumentException("Request requires a color printer");
    if (contracts.hasPrinterOverlap(selectedPrinter, request.getId(), request.getStartDate(), request.getEndDate()))
      throw new RentalConflictException("Printer already has a rental contract for this period");
    if (!printer.isColorCapable()
        && (input.includedColorPages() != 0 || input.colorPagePrice().signum() != 0)) {
      throw new IllegalArgumentException(
          "Monochrome printer requires zero color allowance and price");
    }
    return persist(
        new RentalContract(request, input, clock.instant()),
        "ContractCreated",
        request.getOwnerSubject());
  }

  public RentalContract get(UUID id) {
    var contract = contracts.findById(id).orElseThrow(() -> new ContractNotFoundException(id));
    var request =
        requests
            .findById(contract.getRentalRequestId())
            .orElseThrow(() -> new ContractNotFoundException(id));
    access.requireOwner(request.getOwnerSubject());
    return contract;
  }

  @Transactional
  public RentalContract activate(UUID id, ActivateContract input) {
    access.requireStaff();
    var contract = get(id);
    // Same lock order as printer assignment: printer, then request.
    var printer =
        printers
            .lockById(contract.getPrinterId())
            .orElseThrow(() -> new PrinterNotFoundException(contract.getPrinterId()));
    var request =
        requests
            .lockById(contract.getRentalRequestId())
            .orElseThrow(() -> new RentalNotFoundException(contract.getRentalRequestId()));
    if (!input.version().equals(contract.getVersion())) {
      throw new RentalConflictException("Contract changed; reload it and use its current version");
    }
    if (contract.getStatus() != ContractStatus.DRAFT) {
      throw new RentalConflictException("Only a DRAFT contract can be activated");
    }
    if (request.getStatus() != RentalStatus.APPROVED
        || !contract.getStartDate().equals(request.getStartDate())
        || !contract.getEndDate().equals(request.getEndDate())) {
      throw new RentalConflictException(
          "Approved request must match the contract dates");
    }
    var today = java.time.LocalDate.now(clock);
    if (today.isBefore(contract.getStartDate()) || today.isAfter(contract.getEndDate())) {
      throw new RentalConflictException(
          "Activation is allowed only during the rental period (UTC)");
    }
    if (!contract.getStartDate().equals(input.initialReadingDate())) {
      throw new IllegalArgumentException(
          "Initial readings must be recorded for the contract start date");
    }
    if (!printer.isColorCapable() && input.initialColorReading() != 0) {
      throw new IllegalArgumentException("Monochrome printer requires a zero color reading");
    }
    if (contracts.hasPrinterOverlap(
        printer.getId(),
        request.getId(),
        contract.getStartDate(),
        contract.getEndDate())) {
      throw new RentalConflictException("Printer already has a rental contract for this period");
    }
    printers.clearOtherCustomerLocation(printer.getId(), request.getOwnerSubject());
    contract.activate(input, clock.instant());
    return persist(contract, "ContractActivated", request.getOwnerSubject());
  }

  @Transactional
  public RentalContract complete(UUID id, CompleteContract input) {
    access.requireStaff();
    var contract = get(id);
    // Serialize completion with assignment and activation using the same lock order.
    var printer =
        printers
            .lockById(contract.getPrinterId())
            .orElseThrow(() -> new PrinterNotFoundException(contract.getPrinterId()));
    var request =
        requests
            .lockById(contract.getRentalRequestId())
            .orElseThrow(() -> new RentalNotFoundException(contract.getRentalRequestId()));
    if (!input.version().equals(contract.getVersion())) {
      throw new RentalConflictException("Contract changed; reload it and use its current version");
    }
    if (contract.getStatus() != ContractStatus.ACTIVE) {
      throw new RentalConflictException("Only an ACTIVE contract can be completed");
    }
    if (request.getStatus() != RentalStatus.APPROVED
        || !contract.getPrinterId().equals(request.getPrinterId())
        || !contract.getStartDate().equals(request.getStartDate())
        || !contract.getEndDate().equals(request.getEndDate())) {
      throw new RentalConflictException(
          "Approved request must match the contract printer and dates");
    }
    if (input.finalReadingDate().isAfter(java.time.LocalDate.now(clock))) {
      throw new IllegalArgumentException("Final reading date must not be in the future (UTC)");
    }
    if (!printer.isColorCapable() && input.finalColorReading() != 0) {
      throw new IllegalArgumentException("Monochrome printer requires a zero color reading");
    }
    var now = clock.instant();
    contract.complete(input, now);
    request.transitionTo(RentalStatus.COMPLETED, now);
    // Both managed entities are flushed and committed in this transaction.
    return persist(contract, "ContractCompleted", request.getOwnerSubject());
  }

  public Page<RentalContract> list(int page, int size) {
    var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    return currentUser.isStaff()
        ? contracts.findAll(pageable)
        : contracts.findOwned(currentUser.subject(), pageable);
  }

  private RentalContract persist(RentalContract contract, String type, String owner) {
    var saved = contracts.saveAndFlush(contract);
    events.record(
        type,
        saved.getId(),
        owner,
        java.util.Map.of(
            "status",
            saved.getStatus().name(),
            "rentalRequestId",
            saved.getRentalRequestId().toString()));
    accounting.changed(saved.getId(), owner);
    return saved;
  }
}
