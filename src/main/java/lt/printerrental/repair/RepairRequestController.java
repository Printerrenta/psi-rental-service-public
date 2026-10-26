package lt.printerrental.repair;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repair-requests")
public class RepairRequestController {
  private final RepairRequestService service;

  public RepairRequestController(RepairRequestService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<RepairResponse> create(@Valid @RequestBody CreateRepairRequest input) {
    var response = service.create(input);
    return ResponseEntity.created(URI.create("/api/repair-requests/" + response.id()))
        .body(response);
  }

  @GetMapping("/{id}")
  public RepairResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @GetMapping
  public RepairPage list(
      @RequestParam(required = false) UUID contractId,
      @RequestParam(required = false) RepairStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    var result = service.list(contractId, status, page, size);
    return new RepairPage(
        result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
  }

  @PatchMapping("/{id}/status")
  public RepairResponse changeStatus(
      @PathVariable UUID id, @Valid @RequestBody ChangeRepairStatus input) {
    return service.changeStatus(id, input);
  }

  public record RepairPage(
      List<RepairResponse> content, int page, int size, long totalElements, int totalPages) {}
}
