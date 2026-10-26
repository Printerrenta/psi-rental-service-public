package lt.printerrental.accounting;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/contracts/{id}/accounting")
public class ContractAccountingController {
  private final ContractAccountingService service;

  public ContractAccountingController(ContractAccountingService service) {
    this.service = service;
  }

  @GetMapping
  public AccountingSnapshot get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping
  public AccountingSnapshot initialize(
      @PathVariable UUID id, @RequestBody ContractAccountingService.Initialize input) {
    return service.initialize(id, input);
  }

  @PostMapping("/terms")
  public AccountingSnapshot change(
      @PathVariable UUID id, @RequestBody ContractAccountingService.Change input) {
    return service.change(id, input);
  }
}
