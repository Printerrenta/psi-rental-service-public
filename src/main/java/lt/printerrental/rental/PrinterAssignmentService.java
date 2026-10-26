package lt.printerrental.rental;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrinterAssignmentService {
  private final PrinterRepository printers;
  private final RentalRequestRepository requests;
  private final Clock clock;
  private final lt.printerrental.auth.RentalAccessPolicy access;
  private final lt.printerrental.events.EventRecorder events;

  public PrinterAssignmentService(
      PrinterRepository printers,
      RentalRequestRepository requests,
      Clock clock,
      lt.printerrental.auth.RentalAccessPolicy access,
      lt.printerrental.events.EventRecorder events) {
    this.printers = printers;
    this.requests = requests;
    this.clock = clock;
    this.access = access;
    this.events = events;
  }

  @Transactional
  public RentalResponse assign(UUID id, AssignPrinter input) {
    access.requireStaff();
    // Serialize assignments to the same printer before checking date overlap.
    var printer =
        printers
            .lockById(input.printerId())
            .orElseThrow(() -> new PrinterNotFoundException(input.printerId()));
    var request = requests.findById(id).orElseThrow(() -> new RentalNotFoundException(id));
    if (!input.version().equals(request.getVersion())) {
      throw new RentalConflictException("Request changed; reload it and use its current version");
    }
    if (request.getStatus() != RentalStatus.IN_REVIEW) {
      throw new RentalConflictException("Printer assignment requires IN_REVIEW status");
    }
    if (request.getColorPrintingRequired() == null) {
      throw new RentalConflictException("Historical request has unknown printing requirements");
    }
    if (request.getColorPrintingRequired() && !printer.isColorCapable()) {
      throw new RentalConflictException("Request requires a color printer");
    }
    if (requests.hasOverlap(
        printer.getId(),
        id,
        request.getStartDate(),
        request.getEndDate(),
        List.of(RentalStatus.IN_REVIEW, RentalStatus.APPROVED))) {
      throw new RentalConflictException("Printer is already assigned for this period");
    }
    request.assignPrinter(printer.getId(), clock.instant());
    var saved = requests.saveAndFlush(request);
    events.record(
        "PrinterAssigned",
        saved.getId(),
        saved.getOwnerSubject(),
        java.util.Map.of("printerId", printer.getId().toString()));
    return RentalResponse.from(saved);
  }
}
