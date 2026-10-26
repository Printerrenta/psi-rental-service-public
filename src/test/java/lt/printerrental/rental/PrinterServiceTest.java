package lt.printerrental.rental;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import lt.printerrental.auth.RentalAccessPolicy;
import org.junit.jupiter.api.Test;

class PrinterServiceTest {
  private final PrinterRepository repository = mock(PrinterRepository.class);
  private final PrinterService service =
      new PrinterService(repository, mock(RentalAccessPolicy.class));

  @Test
  void normalizesSerialBeforePersistingAndKeepsEntityEncapsulated() {
    when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    var printer = service.create(new CreatePrinter("  serial-1  ", " Maker ", " Model ", true));
    assertThat(printer.getSerialNumber()).isEqualTo("SERIAL-1");
    assertThat(printer.getManufacturer()).isEqualTo("Maker");
    assertThat(printer.getId()).isNotNull();
  }

  @Test
  void translatesDuplicateSerialToApplicationConflict() {
    when(repository.saveAndFlush(any()))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
    assertThatThrownBy(() -> service.create(new CreatePrinter("serial", "Maker", "Model", false)))
        .isInstanceOf(RentalConflictException.class);
  }
}
