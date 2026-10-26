package lt.printerrental.rental;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "printers")
public class Printer {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 100)
  private String serialNumber;

  @Column(nullable = false, length = 100)
  private String manufacturer;

  @Column(nullable = false, length = 120)
  private String model;

  @Column(nullable = false)
  private boolean colorCapable;

  protected Printer() {}

  Printer(CreatePrinter input) {
    id = UUID.randomUUID();
    serialNumber = input.serialNumber().strip().toUpperCase(Locale.ROOT);
    if (serialNumber.length() > 100) {
      throw new IllegalArgumentException("Normalized serialNumber must not exceed 100 characters");
    }
    manufacturer = input.manufacturer().strip();
    model = input.model().strip();
    colorCapable = input.colorCapable();
  }

  public UUID getId() {
    return id;
  }

  public String getSerialNumber() {
    return serialNumber;
  }

  public String getManufacturer() {
    return manufacturer;
  }

  public String getModel() {
    return model;
  }

  public boolean isColorCapable() {
    return colorCapable;
  }
}
