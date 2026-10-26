package lt.printerrental.rental;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rental-requests")
public class PrinterAssignmentController {
  private final PrinterAssignmentService service;

  public PrinterAssignmentController(PrinterAssignmentService service) {
    this.service = service;
  }

  @PatchMapping("/{id}/printer")
  public RentalResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignPrinter input) {
    return service.assign(id, input);
  }
}
