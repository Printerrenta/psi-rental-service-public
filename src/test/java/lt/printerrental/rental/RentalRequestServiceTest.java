package lt.printerrental.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RentalRequestServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
  @Mock RentalRequestRepository repository;
  @Mock lt.printerrental.auth.CurrentUser currentUser;
  @Mock lt.printerrental.events.EventRecorder events;
  @Mock lt.printerrental.auth.CustomerDirectory customers;
  RentalRequestService service;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(currentUser.subject()).thenReturn("test-admin");
    org.mockito.Mockito.lenient().when(currentUser.isStaff()).thenReturn(true);
    service =
        new RentalRequestService(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC),
            currentUser,
            new lt.printerrental.auth.RentalAccessPolicy(currentUser),
            events, customers);
  }

  private CreateRentalRequest input(LocalDate start, LocalDate end) {
    return new CreateRentalRequest(
        " Jonas ", "jonas@example.com", 1000, false, start, end, " Notes ");
  }
  @Test void staffSelectsVerifiedCustomerInsteadOfBecomingOwner() {
    when(customers.get("alice")).thenReturn(new lt.printerrental.auth.AppUser("alice","real@example.com","Alice",lt.printerrental.auth.UserRole.CUSTOMER));
    when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    var result=service.create(new CreateRentalRequest("Alice","untrusted@example.com",1000,false,TODAY,TODAY,null,"alice"));
    var captor=org.mockito.ArgumentCaptor.forClass(RentalRequest.class);
    org.mockito.Mockito.verify(repository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getOwnerSubject()).isEqualTo("alice");
    assertThat(result.customerEmail()).isEqualTo("real@example.com");
    org.mockito.Mockito.verify(events).record(org.mockito.ArgumentMatchers.eq("RentalRequestCreated"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("alice"),org.mockito.ArgumentMatchers.any());
  }
  @Test void customerCannotSupplyAnOwnerEvenByCallingServiceDirectly() {
    when(currentUser.isStaff()).thenReturn(false);
    assertThatThrownBy(() -> service.create(new CreateRentalRequest("Bob","bob@example.com",1000,false,TODAY,TODAY,null,"bob")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(customers, repository, events);
  }
  @Test void rejectsSelectedStaffAccount() {
    when(customers.get("employee")).thenReturn(new lt.printerrental.auth.AppUser("employee","staff@example.com","Employee",lt.printerrental.auth.UserRole.EMPLOYEE));
    assertThatThrownBy(() -> service.create(new CreateRentalRequest("Staff","staff@example.com",1000,false,TODAY,TODAY,null,"employee")))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository, events);
  }

  @Test
  void acceptsSameDayRentalAndAssignsPendingAndTimestamps() {
    when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var result = service.create(input(TODAY, TODAY));
    assertThat(result.id()).isNotNull();
    assertThat(result.resourceId()).isNull();
    assertThat(result.estimatedMonthlyPages()).isEqualTo(1000);
    assertThat(result.colorPrintingRequired()).isFalse();
    assertThat(result.customerName()).isEqualTo("Jonas");
    assertThat(result.notes()).isEqualTo("Notes");
    assertThat(result.status()).isEqualTo(RentalStatus.PENDING);
    assertThat(result.createdAt()).isEqualTo(NOW);
    assertThat(result.updatedAt()).isEqualTo(NOW);
  }

  @Test
  void rejectsPastStart() {
    assertThatThrownBy(() -> service.create(input(TODAY.minusDays(1), TODAY)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("startDate");
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsReversedDates() {
    assertThatThrownBy(() -> service.create(input(TODAY.plusDays(1), TODAY)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endDate");
    verifyNoInteractions(repository);
  }

  @Test
  void missingRequestReturnsNotFound() {
    var id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());
    when(repository.lockById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(id)).isInstanceOf(RentalNotFoundException.class);
    assertThatThrownBy(
            () -> service.changeStatus(id, new ChangeRentalStatus(RentalStatus.APPROVED, 0L)))
        .isInstanceOf(RentalNotFoundException.class);
  }

  @Test
  void rejectsStaleVersionWithoutChangingStatus() {
    var entity = new RentalRequest(input(TODAY, TODAY), NOW);
    ReflectionTestUtils.setField(entity, "version", 2L);
    when(repository.lockById(entity.getId())).thenReturn(Optional.of(entity));
    assertThatThrownBy(
            () ->
                service.changeStatus(
                    entity.getId(), new ChangeRentalStatus(RentalStatus.APPROVED, 1L)))
        .isInstanceOf(RentalConflictException.class);
    assertThat(entity.getStatus()).isEqualTo(RentalStatus.PENDING);
  }

  @Test
  void approvesRequestWithoutPrinterAndUpdatesTimestamp() {
    var entity = new RentalRequest(input(TODAY, TODAY), NOW.minusSeconds(60));
    entity.transitionTo(RentalStatus.IN_REVIEW, NOW.minusSeconds(30));
    ReflectionTestUtils.setField(entity, "version", 0L);
    when(repository.lockById(entity.getId())).thenReturn(Optional.of(entity));
    when(repository.saveAndFlush(entity)).thenReturn(entity);
    var result =
        service.changeStatus(entity.getId(), new ChangeRentalStatus(RentalStatus.APPROVED, 0L));
    assertThat(result.status()).isEqualTo(RentalStatus.APPROVED);
    assertThat(result.printerId()).isNull();
    assertThat(result.updatedAt()).isEqualTo(NOW);
    assertThat(result.createdAt()).isEqualTo(NOW.minusSeconds(60));
  }

  @Test
  void rejectsTerminalStatusChange() {
    var entity = new RentalRequest(input(TODAY, TODAY), NOW);
    entity.transitionTo(RentalStatus.REJECTED, NOW);
    ReflectionTestUtils.setField(entity, "version", 1L);
    when(repository.lockById(entity.getId())).thenReturn(Optional.of(entity));
    assertThatThrownBy(
            () ->
                service.changeStatus(
                    entity.getId(), new ChangeRentalStatus(RentalStatus.APPROVED, 1L)))
        .isInstanceOf(RentalConflictException.class);
    assertThat(entity.getStatus()).isEqualTo(RentalStatus.REJECTED);
  }
}
