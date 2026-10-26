package lt.printerrental.rental;

import java.util.UUID;

public class PrinterNotFoundException extends RuntimeException {
  public PrinterNotFoundException(UUID id) {
    super("Printer not found: " + id);
  }
}
