package lt.printerrental.rental;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rental-requests")
public class RentalRequestController {
  private final RentalRequestService service;

  public RentalRequestController(RentalRequestService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<RentalResponse> create(@Valid @RequestBody CreateRentalRequest input) {
    var response = service.create(input);
    return ResponseEntity.created(URI.create("/api/rental-requests/" + response.id()))
        .body(response);
  }

  @GetMapping("/{id}")
  public RentalResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @GetMapping
  public RentalPage list(
      @RequestParam(required = false) RentalStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    var result = service.list(status, page, size);
    return new RentalPage(
        result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
  }

  @PatchMapping("/{id}/status")
  public RentalResponse changeStatus(
      @PathVariable UUID id, @Valid @RequestBody ChangeRentalStatus input) {
    return service.changeStatus(id, input);
  }

  public record RentalPage(
      List<RentalResponse> content, int page, int size, long totalElements, int totalPages) {}
}
