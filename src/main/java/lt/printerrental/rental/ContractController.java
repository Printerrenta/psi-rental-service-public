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
@RequestMapping("/api/contracts")
public class ContractController {
  private final ContractService service;

  public ContractController(ContractService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<RentalContract> create(@Valid @RequestBody CreateContract input) {
    var contract = service.create(input);
    return ResponseEntity.created(URI.create("/api/contracts/" + contract.getId())).body(contract);
  }

  @GetMapping("/{id}")
  public RentalContract get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping("/{id}/activate")
  public RentalContract activate(
      @PathVariable UUID id, @Valid @RequestBody ActivateContract input) {
    return service.activate(id, input);
  }

  @PostMapping("/{id}/complete")
  public RentalContract complete(
      @PathVariable UUID id, @Valid @RequestBody CompleteContract input) {
    return service.complete(id, input);
  }

  @GetMapping
  public ContractPage list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    var result = service.list(page, size);
    return new ContractPage(
        result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
  }

  public record ContractPage(
      List<RentalContract> content, int page, int size, long totalElements, int totalPages) {}
}
