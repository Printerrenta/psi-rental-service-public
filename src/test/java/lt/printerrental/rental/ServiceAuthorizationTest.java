package lt.printerrental.rental;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import lt.printerrental.auth.*;
import lt.printerrental.events.EventRecorder;
import org.junit.jupiter.api.Test;

class ServiceAuthorizationTest {
  @Test
  void customerCannotBypassHttpSecurityByCallingStaffUseCases() {
    var user = mock(CurrentUser.class);
    var access = new RentalAccessPolicy(user);
    var printers = mock(PrinterRepository.class);
    var requests = mock(RentalRequestRepository.class);
    var contracts = mock(ContractRepository.class);
    var events = mock(EventRecorder.class);
    var catalog = new PrinterService(printers, access);
    var rentals = new RentalRequestService(requests, Clock.systemUTC(), user, access, events);
    var assignments =
        new PrinterAssignmentService(printers, requests, Clock.systemUTC(), access, events);
    var contract =
        new ContractService(
            contracts,
            requests,
            printers,
            Clock.systemUTC(),
            user,
            access,
            events,
            org.mockito.Mockito.mock(ContractAccountingObserver.class));
    for (Runnable action :
        java.util.List.<Runnable>of(
            () -> catalog.create(null),
            () -> catalog.get(null),
            () -> catalog.list(0, 20),
            () -> rentals.changeStatus(null, null),
            () -> assignments.assign(null, null),
            () -> contract.create(null),
            () -> contract.activate(null, null),
            () -> contract.complete(null, null))) {
      assertThatThrownBy(action::run)
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    verifyNoInteractions(printers, requests, contracts, events);
  }
}
