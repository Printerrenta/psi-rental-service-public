package lt.printerrental.rental;

import java.util.UUID;

public class RentalNotFoundException extends RuntimeException {
  public RentalNotFoundException(UUID id) {
    super("Rental request not found: " + id);
  }
}
