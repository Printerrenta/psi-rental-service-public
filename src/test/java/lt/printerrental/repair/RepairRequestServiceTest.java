package lt.printerrental.repair;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import lt.printerrental.auth.*;
import lt.printerrental.events.EventRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;

class RepairRequestServiceTest {
  private final RepairRequestRepository repository = mock(RepairRequestRepository.class);
  private final RepairContractAccess contracts = mock(RepairContractAccess.class);
  private final CurrentUser user = mock(CurrentUser.class);
  private final EventRecorder events = mock(EventRecorder.class);
  private final RepairRequestService service =
      new RepairRequestService(
          repository,
          contracts,
          user,
          new RentalAccessPolicy(user),
          events,
          Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

  private RepairRequest owned() {
    when(user.subject()).thenReturn("alice");
    return new RepairRequest(
        new RepairContractAccess.ActiveContract(UUID.randomUUID(), UUID.randomUUID(), "alice"),
        "Printer rollers are broken",
        Instant.EPOCH);
  }

  @Test
  void directCustomerCallsCannotStartOrCompleteRepairs() {
    var r = owned();
    when(repository.lockById(r.getId())).thenReturn(Optional.of(r));
    for (var next : List.of(RepairStatus.IN_PROGRESS, RepairStatus.COMPLETED))
      assertThatThrownBy(
              () ->
                  service.changeStatus(
                      r.getId(), new ChangeRepairStatus(next, 0L, "Replaced rollers")))
          .isInstanceOf(AccessDeniedException.class);
    verify(repository, never()).saveAndFlush(any());
    verifyNoInteractions(events);
  }

  @Test
  void ownershipChecksAlsoApplyToDirectReads() {
    var r = owned();
    when(user.subject()).thenReturn("bob");
    when(repository.findById(r.getId())).thenReturn(Optional.of(r));
    assertThatThrownBy(() -> service.get(r.getId())).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void customerCannotCancelAnInProgressRepair() {
    var r = owned();
    r.transitionTo(RepairStatus.IN_PROGRESS, null, Instant.EPOCH);
    when(repository.lockById(r.getId())).thenReturn(Optional.of(r));
    assertThatThrownBy(
            () ->
                service.changeStatus(
                    r.getId(), new ChangeRepairStatus(RepairStatus.CANCELLED, 0L, null)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(events);
  }

  @Test
  void listingAlwaysIncludesCustomerOwnership() {
    when(user.subject()).thenReturn("alice");
    when(repository.search(eq("alice"), isNull(), isNull(), any())).thenReturn(Page.empty());
    service.list(null, null, 0, 20);
    verify(repository).search(eq("alice"), isNull(), isNull(), any());
  }

  @Test
  void eventsExcludeFaultDescriptionsAndResolution() {
    var id = UUID.randomUUID();
    var contract = new RepairContractAccess.ActiveContract(id, UUID.randomUUID(), "alice");
    when(contracts.requireActive(id)).thenReturn(contract);
    when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    var saved = service.create(new CreateRepairRequest(id, "Private fault description"));
    verify(events)
        .record(
            eq("RepairRequestCreated"),
            eq(saved.id()),
            eq("alice"),
            eq(
                Map.of(
                    "status",
                    "OPEN",
                    "contractId",
                    id.toString(),
                    "printerId",
                    contract.printerId().toString())));
  }

  @Test
  void duplicateOpenRequestNeverRecordsAnotherEvent() {
    var id = UUID.randomUUID();
    when(contracts.requireActive(id))
        .thenReturn(new RepairContractAccess.ActiveContract(id, UUID.randomUUID(), "alice"));
    when(repository.existsByContractIdAndStatusIn(eq(id), any())).thenReturn(true);
    assertThatThrownBy(() -> service.create(new CreateRepairRequest(id, "Printer does not work")))
        .isInstanceOf(RepairConflictException.class);
    verifyNoInteractions(events);
    verify(repository, never()).saveAndFlush(any());
  }
}
