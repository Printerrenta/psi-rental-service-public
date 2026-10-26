package lt.printerrental.repair;

import java.util.UUID;

public class RepairNotFoundException extends RuntimeException {
  public RepairNotFoundException(UUID id) {
    super("Repair request not found: " + id);
  }
}
