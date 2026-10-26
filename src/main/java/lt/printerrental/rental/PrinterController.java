package lt.printerrental.rental;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/printers")
public class PrinterController {
  private final PrinterService service;

  public PrinterController(PrinterService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<Printer> create(@Valid @RequestBody CreatePrinter input) {
    var printer = service.create(input);
    return ResponseEntity.created(URI.create("/api/printers/" + printer.getId())).body(printer);
  }

  @GetMapping("/{id}")
  public Printer get(@PathVariable UUID id) {
    return service.get(id);
  }

  @GetMapping
  public PrinterPage list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    var result = service.list(page, size);
    return new PrinterPage(
        result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
  }

  public record PrinterPage(
      List<Printer> content, int page, int size, long totalElements, int totalPages) {}
}
