package lt.printerrental.rental;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;
import lt.printerrental.accounting.AccountingRepository;
import lt.printerrental.transport.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CompletionReadingsTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final AccountingRepository accounting = mock(AccountingRepository.class);
  final ServiceHttpClient client = mock(ServiceHttpClient.class);
  final RentalContract contract = mock(RentalContract.class);
  final UUID id = UUID.randomUUID();
  final LocalDate date = LocalDate.of(2026, 11, 11);
  final CompleteContract input = new CompleteContract(1L, 250L, 0L, date, List.of(250L, 0L, 30L, 0L));
  final CompletionReadings service = new CompletionReadings(accounting, client, json, "http://metering.test");

  @BeforeEach void setup() {
    when(contract.getId()).thenReturn(id);
    when(accounting.find(id)).thenReturn(Optional.of(new AccountingRepository.Settings(true,
        List.of(100L, 0L, 10L, 0L), 1L)));
    var jwt = Jwt.withTokenValue("staff.jwt.signature").header("alg", "EdDSA").subject("staff").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }
  @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

  @SuppressWarnings("unchecked")
  HttpResponse<byte[]> response(int status, Object body) throws Exception {
    var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(json.writeValueAsBytes(body));
    return response;
  }
  Map<String, Object> view(List<?> readings) {
    return Map.of("contract", Map.of("contractId", id), "version", 4, "readings", readings);
  }
  Map<String, Object> reading(String status, String day, List<Long> counts) {
    return Map.of("status", status, "asOf", day, "counters", counts);
  }
  void get(List<?> readings) throws Exception {
    var result = response(200, view(readings));
    when(client.send(anyString(), contains("?page="), eq("GET"), isNull(), isNull(), anyString()))
        .thenReturn(result);
  }

  @Test void sendsFourFinalCountersAndReusesIntentAfterAnUncertainResponse() throws Exception {
    get(List.of());
    var accepted = response(200, Map.of("reading", Map.of("status", "ACCEPTED")));
    when(client.send(anyString(), endsWith("/readings"), eq("POST"), any(), eq("application/json"), anyString()))
        .thenThrow(new UpstreamUnavailableException())
        .thenReturn(accepted);
    assertThatThrownBy(() -> service.record(contract, input)).isInstanceOf(UpstreamUnavailableException.class);
    service.record(contract, input);
    var bodies = org.mockito.ArgumentCaptor.forClass(byte[].class);
    verify(client, times(2)).send(eq("http://metering.test"), endsWith("/readings"), eq("POST"),
        bodies.capture(), eq("application/json"), eq("Bearer staff.jwt.signature"));
    assertThat(bodies.getAllValues().get(0)).isEqualTo(bodies.getAllValues().get(1));
    var body = json.readTree(bodies.getValue());
    assertThat(body.path("counters").toString()).isEqualTo(json.writeValueAsString(input.finalCounters()));
    assertThat(body.path("version").asLong()).isEqualTo(4);
  }

  @Test void acceptedMatchingReadingMakesRetryReadOnly() throws Exception {
    get(List.of(reading("ACCEPTED", date.toString(), input.finalCounters())));
    service.record(contract, input);
    verify(client, never()).send(anyString(), anyString(), eq("POST"), any(), any(), any());
  }

  @Test void checksLaterPagesBeforeAcceptingAnExistingFinalReading() throws Exception {
    var first = response(200, view(java.util.Collections.nCopies(100,
        reading("REJECTED", date.minusDays(1).toString(), input.finalCounters()))));
    var second = response(200, view(List.of(reading("ACCEPTED", date.toString(), input.finalCounters()))));
    when(client.send(anyString(), contains("?page="), eq("GET"), isNull(), isNull(), anyString()))
        .thenReturn(first, second);
    service.record(contract, input);
    verify(client).send(anyString(), endsWith("?page=1&size=100"), eq("GET"), isNull(), isNull(), anyString());
    verify(client, never()).send(anyString(), anyString(), eq("POST"), any(), any(), any());
  }

  @Test void refusesAHistoryThatChangesBetweenPages() throws Exception {
    var first = response(200, view(java.util.Collections.nCopies(100,
        reading("REJECTED", date.minusDays(1).toString(), input.finalCounters()))));
    var changed = new HashMap<>(view(List.of()));
    changed.put("version", 5);
    var second = response(200, changed);
    when(client.send(anyString(), contains("?page="), eq("GET"), isNull(), isNull(), anyString()))
        .thenReturn(first, second);
    assertThatThrownBy(() -> service.record(contract, input)).isInstanceOf(RentalConflictException.class);
    verify(client, never()).send(anyString(), anyString(), eq("POST"), any(), any(), any());
  }

  @Test void refusesPendingConflictingAndLaterAcceptedReadings() throws Exception {
    for (var row : List.of(reading("PENDING", date.toString(), input.finalCounters()),
        reading("ACCEPTED", date.toString(), List.of(200L, 0L, 20L, 0L)),
        reading("ACCEPTED", date.plusDays(1).toString(), input.finalCounters()))) {
      get(List.of(row));
      assertThatThrownBy(() -> service.record(contract, input)).isInstanceOf(RentalConflictException.class);
    }
    verify(client, never()).send(anyString(), anyString(), eq("POST"), any(), any(), any());
  }

  @Test void doesNotBypassReviewForAnAnomalousFinalReading() throws Exception {
    get(List.of());
    var pending = response(200, Map.of("reading", Map.of("status", "PENDING")));
    when(client.send(anyString(), endsWith("/readings"), eq("POST"), any(), any(), any()))
        .thenReturn(pending);
    assertThatThrownBy(() -> service.record(contract, input)).isInstanceOf(RentalConflictException.class)
        .hasMessageContaining("peržiūros");
  }

  @Test void failsClosedWhenMeteringIsUnavailableOrNotConfigured() throws Exception {
    var unavailable = response(503, Map.of());
    when(client.send(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(unavailable);
    assertThatThrownBy(() -> service.record(contract, input)).isInstanceOf(UpstreamUnavailableException.class);
    var missing = new CompletionReadings(accounting, client, json, "");
    assertThatThrownBy(() -> missing.record(contract, input)).isInstanceOf(UpstreamUnavailableException.class);
  }

  @Test void rejectsMissingA3CountersAndInconsistentA4FieldsBeforeCallingMetering() {
    assertThatThrownBy(() -> service.record(contract, new CompleteContract(1L, 250L, 0L, date)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.record(contract,
        new CompleteContract(1L, 250L, 0L, date, List.of(251L, 0L, 30L, 0L))))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(client);
  }
}
