package lt.printerrental.rental;

import java.util.UUID;
import lt.printerrental.auth.RentalAccessPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PrinterService {
  private final PrinterRepository printers;
  private final RentalAccessPolicy access;

  public PrinterService(PrinterRepository printers, RentalAccessPolicy access) {
    this.printers = printers;
    this.access = access;
  }

  @Transactional
  public Printer create(CreatePrinter input) {
    access.requireStaff();
    try {
      return printers.saveAndFlush(new Printer(input));
    } catch (DataIntegrityViolationException error) {
      throw new RentalConflictException(
          "Printer serial number already exists or violates catalog constraints");
    }
  }

  public Printer get(UUID id) {
    access.requireStaff();
    return printers.findById(id).orElseThrow(() -> new PrinterNotFoundException(id));
  }

  public Page<Printer> list(int page, int size) {
    access.requireStaff();
    return printers.findAll(PageRequest.of(page, size, Sort.by("serialNumber", "id")));
  }
}
