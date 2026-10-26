package lt.printerrental.accounting;

import java.util.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.rental.*;
import org.springframework.stereotype.Component;

@Component
public class AccountingSnapshotPublisher implements ContractAccountingObserver {
  private final AccountingRepository settings;
  private final ContractRepository contracts;
  private final PrinterRepository printers;
  private final EventRecorder events;

  public AccountingSnapshotPublisher(
      AccountingRepository settings,
      ContractRepository contracts,
      PrinterRepository printers,
      EventRecorder events) {
    this.settings = settings;
    this.contracts = contracts;
    this.printers = printers;
    this.events = events;
  }

  public void changed(UUID id, String owner) {
    if (settings.find(id).isPresent()) {
      settings.bump(id);
      events.record(
          "ContractAccountingChanged", id, owner, Map.of("snapshot", snapshot(id, owner)));
    }
  }

  public AccountingSnapshot snapshot(UUID id, String owner) {
    var contract = contracts.findById(id).orElseThrow(() -> new ContractNotFoundException(id));
    var s =
        settings
            .find(id)
            .orElseThrow(
                () ->
                    new RentalConflictException(
                        "Four-counter accounting must be initialized explicitly"));
    var printer =
        printers
            .findById(contract.getPrinterId())
            .orElseThrow(() -> new PrinterNotFoundException(contract.getPrinterId()));
    return new AccountingSnapshot(
        id,
        printer.getId(),
        owner,
        printer.getManufacturer().strip().toLowerCase(Locale.ROOT)
            + "/"
            + printer.getModel().strip().toLowerCase(Locale.ROOT),
        printer.isColorCapable(),
        s.a3Capable(),
        contract.getStatus().name(),
        contract.getStartDate(),
        contract.getEndDate(),
        contract.getFinalReadingDate(),
        contract.getStartDate(),
        s.baseline(),
        s.revision(),
        settings.terms(id));
  }
}
