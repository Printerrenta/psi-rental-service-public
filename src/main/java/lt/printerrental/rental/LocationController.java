package lt.printerrental.rental;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rental-requests/locations")
public class LocationController {
  private final LocationService service;
  public LocationController(LocationService service) { this.service=service; }
  @GetMapping
  public LocationService.LocationPage list(@RequestParam(defaultValue="0") @Min(0) int page,
      @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.list(page,size); }
  @PostMapping
  public ResponseEntity<LocationService.Location> create(@Valid @RequestBody LocationService.Input input) {
    var result=service.create(input);
    return ResponseEntity.created(URI.create("/api/rental-requests/locations/"+result.id())).body(result);
  }
  @PatchMapping("/{id}")
  public LocationService.Location update(@PathVariable UUID id,@Valid @RequestBody LocationService.Update input) {
    return service.update(id,input);
  }
  @PatchMapping("/printers/{id}")
  public LocationService.Location assign(@PathVariable UUID id,@Valid @RequestBody LocationService.Assignment input) {
    return service.assign(id,input);
  }
  @PostMapping("/requests")
  public ResponseEntity<LocationService.RequestView> request(@Valid @RequestBody LocationService.LocatedRequest input) {
    var result=service.createRequest(input);
    return ResponseEntity.created(URI.create("/api/rental-requests/"+result.request().id())).body(result);
  }
  @GetMapping("/requests")
  public LocationService.RequestPage requests(@RequestParam(defaultValue="0") @Min(0) int page,
      @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.listRequests(page,size); }
  @PatchMapping("/requests/{id}")
  public ResponseEntity<Void> updateRequest(@PathVariable UUID id,@Valid @RequestBody LocationService.RequestUpdate input) {
    service.updateRequest(id,input); return ResponseEntity.noContent().build();
  }
}
