package lt.printerrental.rental;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import lt.printerrental.auth.CurrentUser;
import lt.printerrental.auth.RentalAccessPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContractService {
  private final ContractAccountingObserver accounting;
  private final ContractRepository contracts;
  private final RentalRequestRepository requests;
  private final PrinterRepository printers;
  private final Clock clock;
  private final lt.printerrental.events.EventRecorder events;
  private final CurrentUser currentUser;
  private final RentalAccessPolicy access;
  private final CompletionReadings completionReadings;
  private final TransactionOperations readTransaction;
  private final TransactionOperations writeTransaction;

  @Autowired
  public ContractService(
      ContractRepository contracts,
      RentalRequestRepository requests,
      PrinterRepository printers,
      Clock clock,
      CurrentUser currentUser,
      RentalAccessPolicy access,
      lt.printerrental.events.EventRecorder events,
      ContractAccountingObserver accounting,
      CompletionReadings completionReadings,
      PlatformTransactionManager transactionManager) {
    this(
        contracts,
        requests,
        printers,
        clock,
        currentUser,
        access,
        events,
        accounting,
        completionReadings,
        readOnlyTransaction(transactionManager),
        new TransactionTemplate(transactionManager));
  }

  ContractService(
      ContractRepository contracts,
      RentalRequestRepository requests,
      PrinterRepository printers,
      Clock clock,
      CurrentUser currentUser,
      RentalAccessPolicy access,
      lt.printerrental.events.EventRecorder events,
      ContractAccountingObserver accounting,
      CompletionReadings completionReadings,
      TransactionOperations readTransaction,
      TransactionOperations writeTransaction) {
    this.completionReadings = completionReadings;
    this.accounting = accounting;
    this.contracts = contracts;
    this.requests = requests;
    this.printers = printers;
    this.clock = clock;
    this.events = events;
    this.currentUser = currentUser;
    this.access = access;
    this.readTransaction = readTransaction;
    this.writeTransaction = writeTransaction;
  }

  private static TransactionTemplate readOnlyTransaction(
      PlatformTransactionManager transactionManager) {
    var template = new TransactionTemplate(transactionManager);
    template.setReadOnly(true);
    return template;
  }

  @Transactional
  public RentalContract create(CreateContract input) {
    access.requireStaff();
    UUID selectedPrinter =
        input.printerId() != null
            ? input.printerId()
            : requests
                .findLockKeyById(input.rentalRequestId())
                .orElseThrow(() -> new RentalNotFoundException(input.rentalRequestId()))
                .printerId();
    if (selectedPrinter == null) {
      throw new RentalConflictException("Choose a printer when creating the contract");
    }
    // All device operations lock printer before request, including concurrent contract creation.
    var printer =
        printers
            .lockById(selectedPrinter)
            .orElseThrow(() -> new PrinterNotFoundException(selectedPrinter));
    var request =
        requests
            .lockById(input.rentalRequestId())
            .orElseThrow(() -> new RentalNotFoundException(input.rentalRequestId()));
    if (!input.requestVersion().equals(request.getVersion())) {
      throw new RentalConflictException("Request changed; reload it and use its current version");
    }
    if (request.getStatus() != RentalStatus.APPROVED) {
      throw new RentalConflictException("Contract requires an APPROVED request");
    }
    if (contracts.existsByRentalRequestId(request.getId())) {
      throw new RentalConflictException("A contract already exists for this request");
    }
    if (input.printerId() == null && !selectedPrinter.equals(request.getPrinterId()))
      throw new RentalConflictException(
          "Request changed; reload it and choose the contract printer");
    if (Boolean.TRUE.equals(request.getColorPrintingRequired()) && !printer.isColorCapable())
      throw new IllegalArgumentException("Request requires a color printer");
    if (contracts.hasPrinterOverlap(
        selectedPrinter, request.getId(), request.getStartDate(), request.getEndDate()))
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

  @Transactional(readOnly = true)
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
    var lockKeys =
        contracts.findLockKeysById(id).orElseThrow(() -> new ContractNotFoundException(id));
    // Strict deterministic lock order: printer, then request, then contract.
    var printer =
        printers
            .lockById(lockKeys.printerId())
            .orElseThrow(() -> new PrinterNotFoundException(lockKeys.printerId()));
    var request =
        requests
            .lockById(lockKeys.rentalRequestId())
            .orElseThrow(() -> new RentalNotFoundException(lockKeys.rentalRequestId()));
    var contract = contracts.lockById(id).orElseThrow(() -> new ContractNotFoundException(id));
    if (!input.version().equals(contract.getVersion())) {
      throw new RentalConflictException("Contract changed; reload it and use its current version");
    }
    if (contract.getStatus() != ContractStatus.DRAFT) {
      throw new RentalConflictException("Only a DRAFT contract can be activated");
    }
    if (request.getStatus() != RentalStatus.APPROVED
        || !contract.getStartDate().equals(request.getStartDate())
        || !contract.getEndDate().equals(request.getEndDate())) {
      throw new RentalConflictException("Approved request must match the contract dates");
    }
    var today = LocalDate.now(clock);
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

  public RentalContract complete(UUID id, CompleteContract input) {
    access.requireStaff();
    // Step A: short read-only validation without holding row locks or an open tx during HTTP.
    var snapshot =
        readTransaction.execute(
            status -> {
              var contract =
                  contracts.findById(id).orElseThrow(() -> new ContractNotFoundException(id));
              var printer =
                  printers
                      .findById(contract.getPrinterId())
                      .orElseThrow(() -> new PrinterNotFoundException(contract.getPrinterId()));
              var request =
                  requests
                      .findById(contract.getRentalRequestId())
                      .orElseThrow(
                          () -> new RentalNotFoundException(contract.getRentalRequestId()));
              validateCompletion(contract, printer, request, input);
              return contract;
            });
    // Step B: record final readings in metering outside any DB transaction or lock.
    completionReadings.record(snapshot, input);
    // Step C: acquire locks in deterministic order, re-verify state, and commit completion.
    return writeTransaction.execute(
        status -> {
          var lockKeys =
              contracts.findLockKeysById(id).orElseThrow(() -> new ContractNotFoundException(id));
          // Serialize completion with assignment and activation using the same lock order.
          var printer =
              printers
                  .lockById(lockKeys.printerId())
                  .orElseThrow(() -> new PrinterNotFoundException(lockKeys.printerId()));
          var request =
              requests
                  .lockById(lockKeys.rentalRequestId())
                  .orElseThrow(() -> new RentalNotFoundException(lockKeys.rentalRequestId()));
          var contract =
              contracts.lockById(id).orElseThrow(() -> new ContractNotFoundException(id));
          validateCompletion(contract, printer, request, input);
          var now = clock.instant();
          contract.complete(input, now);
          request.transitionTo(RentalStatus.COMPLETED, now);
          // Both managed entities are flushed and committed in this transaction.
          return persist(contract, "ContractCompleted", request.getOwnerSubject());
        });
  }

  @Transactional(readOnly = true)
  public Page<RentalContract> list(int page, int size) {
    var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    return currentUser.isStaff()
        ? contracts.findVisible(pageable)
        : contracts.findOwned(currentUser.subject(), pageable);
  }

  private void validateCompletion(
      RentalContract contract, Printer printer, RentalRequest request, CompleteContract input) {
    if (!input.version().equals(contract.getVersion())) {
      throw new RentalConflictException("Contract changed; reload it and use its current version");
    }
    if (contract.getStatus() != ContractStatus.ACTIVE) {
      throw new RentalConflictException("Only an ACTIVE contract can be completed");
    }
    if (request.getStatus() != RentalStatus.APPROVED
        || !contract.getStartDate().equals(request.getStartDate())
        || !contract.getEndDate().equals(request.getEndDate())) {
      throw new RentalConflictException("Approved request must match the contract dates");
    }
    if (input.finalReadingDate().isAfter(LocalDate.now(clock))) {
      throw new IllegalArgumentException("Final reading date must not be in the future (UTC)");
    }
    if (!printer.isColorCapable() && input.finalColorReading() != 0) {
      throw new IllegalArgumentException("Monochrome printer requires a zero color reading");
    }
    contract.validateCompletion(input);
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
