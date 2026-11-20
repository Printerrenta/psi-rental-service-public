package lt.printerrental.rental;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lt.printerrental.auth.*;
import lt.printerrental.events.EventRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class ContractCompletionTest {
  @Test
  void completesContractWhosePrinterWasChosenAtContractCreation() throws Exception {
    var day = LocalDate.of(2026, 11, 11);
    var requests = mock(RentalRequestRepository.class);
    var contracts = mock(ContractRepository.class);
    var printers = mock(PrinterRepository.class);
    var request = mock(RentalRequest.class);
    var requestId = UUID.randomUUID();
    var printerId = UUID.randomUUID();
    when(request.getId()).thenReturn(requestId);
    when(request.getVersion()).thenReturn(2L);
    when(request.getStatus()).thenReturn(RentalStatus.APPROVED);
    when(request.getStartDate()).thenReturn(day);
    when(request.getEndDate()).thenReturn(day.plusDays(30));
    when(request.getOwnerSubject()).thenReturn("customer");
    when(request.getColorPrintingRequired()).thenReturn(false);
    when(requests.findById(requestId)).thenReturn(Optional.of(request));
    when(requests.lockById(requestId)).thenReturn(Optional.of(request));
    var printer = mock(Printer.class);
    when(printer.getId()).thenReturn(printerId);
    when(printers.findById(printerId)).thenReturn(Optional.of(printer));
    when(printers.lockById(printerId)).thenReturn(Optional.of(printer));
    when(contracts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    var inTx = new AtomicBoolean(false);
    TransactionOperations trackingTx =
        new TransactionOperations() {
          @Override
          public <T> T execute(TransactionCallback<T> action) {
            inTx.set(true);
            try {
              return action.doInTransaction(new SimpleTransactionStatus());
            } finally {
              inTx.set(false);
            }
          }
        };
    var completionReadings = mock(CompletionReadings.class);
    doAnswer(
            call -> {
              assertThat(inTx.get()).isFalse();
              return null;
            })
        .when(completionReadings)
        .record(any(), any());
    var service =
        new ContractService(
            contracts,
            requests,
            printers,
            Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC),
            mock(CurrentUser.class),
            mock(RentalAccessPolicy.class),
            mock(EventRecorder.class),
            mock(ContractAccountingObserver.class),
            completionReadings,
            trackingTx,
            trackingTx);
    var draft =
        service.create(
            new CreateContract(
                requestId,
                2L,
                new BigDecimal("35.00"),
                0,
                0,
                new BigDecimal("0.0100"),
                new BigDecimal("0.0000"),
                printerId));
    var version = RentalContract.class.getDeclaredField("version");
    version.setAccessible(true);
    version.set(draft, 0L);
    when(contracts.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(contracts.findLockKeysById(draft.getId()))
        .thenReturn(Optional.of(new ContractLockKeys(printerId, requestId)));
    when(contracts.lockById(draft.getId())).thenReturn(Optional.of(draft));
    service.activate(draft.getId(), new ActivateContract(0L, 100L, 0L, day));
    version.set(draft, 1L);
    assertThat(request.getPrinterId()).isNull();
    assertThat(service.complete(draft.getId(), new CompleteContract(1L, 250L, 0L, day)).getStatus())
        .isEqualTo(ContractStatus.COMPLETED);
    verify(completionReadings).record(any(), any());
  }

  @Test
  void createWithoutExplicitPrinterUsesScalarLookupBeforeLocking() {
    var day = LocalDate.of(2026, 11, 11);
    var requests = mock(RentalRequestRepository.class);
    var contracts = mock(ContractRepository.class);
    var printers = mock(PrinterRepository.class);
    var request = mock(RentalRequest.class);
    var requestId = UUID.randomUUID();
    var printerId = UUID.randomUUID();
    when(request.getId()).thenReturn(requestId);
    when(request.getPrinterId()).thenReturn(printerId);
    when(request.getVersion()).thenReturn(2L);
    when(request.getStatus()).thenReturn(RentalStatus.APPROVED);
    when(request.getStartDate()).thenReturn(day);
    when(request.getEndDate()).thenReturn(day.plusDays(30));
    when(request.getOwnerSubject()).thenReturn("customer");
    when(request.getColorPrintingRequired()).thenReturn(false);
    when(requests.findLockKeyById(requestId))
        .thenReturn(Optional.of(new RequestLockKey(requestId, printerId)));
    when(requests.lockById(requestId)).thenReturn(Optional.of(request));
    var printer = mock(Printer.class);
    when(printers.lockById(printerId)).thenReturn(Optional.of(printer));
    when(contracts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    var service =
        new ContractService(
            contracts,
            requests,
            printers,
            Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC),
            mock(CurrentUser.class),
            mock(RentalAccessPolicy.class),
            mock(EventRecorder.class),
            mock(ContractAccountingObserver.class),
            mock(CompletionReadings.class),
            TransactionOperations.withoutTransaction(),
            TransactionOperations.withoutTransaction());
    var draft =
        service.create(
            new CreateContract(
                requestId,
                2L,
                new BigDecimal("35.00"),
                0,
                0,
                new BigDecimal("0.0100"),
                new BigDecimal("0.0000")));
    assertThat(draft.getPrinterId()).isEqualTo(printerId);
    verify(requests, never()).findById(any());
    var order = inOrder(requests, printers);
    order.verify(requests).findLockKeyById(requestId);
    order.verify(printers).lockById(printerId);
    order.verify(requests).lockById(requestId);
  }
}
