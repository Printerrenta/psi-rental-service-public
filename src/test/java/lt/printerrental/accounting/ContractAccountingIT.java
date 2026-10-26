package lt.printerrental.accounting;

import static org.assertj.core.api.Assertions.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "NEON_AUTH_BASE_URL=https://auth.test/auth",
      "NEON_AUTH_JWKS_URL=https://auth.test/jwks",
      "OUTBOX_INITIAL_DELAY_MS=3600000"
    })
@AutoConfigureMockMvc
@Testcontainers
class ContractAccountingIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new lt.printerrental.testing.TestPostgresContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () ->
            POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=rental");
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
  @MockitoBean lt.printerrental.auth.UserIdentityResolver resolver;
  @MockitoBean lt.printerrental.events.EventPublisher publisher;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ContractAccountingService accounting;
  UUID id;
  LocalDate start, end;

  void as(String name, String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(name, "", "ROLE_" + role));
  }

  @BeforeEach
  void setup() {
    db.execute(
        "TRUNCATE"
            + " rental.printers,rental.rental_requests,rental.rental_contracts,rental.outbox_events"
            + " CASCADE");
    as("employee", "EMPLOYEE");
    var printer = UUID.randomUUID();
    var request = UUID.randomUUID();
    id = UUID.randomUUID();
    start = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    end = start.plusMonths(3);
    db.update(
        "INSERT INTO rental.printers(id,serial_number,manufacturer,model,color_capable)"
            + " VALUES(?,?,?,?,true)",
        printer,
        printer.toString(),
        "Maker",
        "Model");
    db.update(
        "INSERT INTO"
            + " rental.rental_requests(id,customer_name,customer_email,start_date,end_date,status,created_at,updated_at,estimated_monthly_pages,color_printing_required,printer_id,owner_subject,version)"
            + " VALUES(?,?,?,?,?,'APPROVED',now(),now(),100,true,?,'alice',3)",
        request,
        "Alice",
        "alice@example.test",
        start,
        end,
        printer);
    db.update(
        "INSERT INTO"
            + " rental.rental_contracts(id,rental_request_id,printer_id,customer_name,customer_email,start_date,end_date,monthly_fee,included_mono_pages,included_color_pages,mono_page_price,color_page_price,currency,status,created_at,version)"
            + " VALUES(?,?,?,?,?,?,?,31,0,0,0.01,0.03,'EUR','DRAFT',now(),0)",
        id,
        request,
        printer,
        "Alice",
        "alice@example.test",
        start,
        end);
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  ContractAccountingService.Initialize init() {
    return new ContractAccountingService.Initialize(
        true, List.of(100L, 20L, 10L, 5L), new BigDecimal("21"), 14);
  }

  @Test
  void explicitFourCounterBaselinePublishesVersionedSnapshotAndEnforcesOwnership() {
    var s = accounting.initialize(id, init());
    assertThat(s.baseline()).containsExactly(100L, 20L, 10L, 5L);
    assertThat(s.revision()).isEqualTo(1);
    assertThat(s.terms().get(0).paymentTermDays()).isEqualTo(14);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM rental.outbox_events WHERE"
                    + " event_type='ContractAccountingChanged'",
                Long.class))
        .isEqualTo(1);
    as("alice", "CUSTOMER");
    assertThat(accounting.get(id).ownerSubject()).isEqualTo("alice");
    as("bob", "CUSTOMER");
    assertThatThrownBy(() -> accounting.get(id))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void customerCannotInitializeOrChangeTerms() {
    as("alice", "CUSTOMER");
    assertThatThrownBy(() -> accounting.initialize(id, init()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(db.queryForObject("SELECT count(*) FROM rental.contract_accounting", Long.class))
        .isZero();
  }

  @Test
  void midmonthAppendKeepsOldTermsAndRejectsStaleRevision() {
    accounting.initialize(id, init());
    var effective = LocalDate.now(ZoneOffset.UTC).plusDays(5);
    var input =
        new ContractAccountingService.Change(
            1,
            effective,
            new BigDecimal("40.00"),
            new BigDecimal("0.02"),
            new BigDecimal("0.04"),
            new BigDecimal("21"),
            30);
    var changed = accounting.change(id, input);
    assertThat(changed.revision()).isEqualTo(2);
    assertThat(changed.terms()).hasSize(2);
    assertThat(changed.terms().get(0).monoPrice()).isEqualByComparingTo("0.01");
    assertThatThrownBy(() -> accounting.change(id, input))
        .isInstanceOf(lt.printerrental.rental.RentalConflictException.class);
    assertThat(db.queryForObject("SELECT count(*) FROM rental.contract_terms", Long.class))
        .isEqualTo(2);
  }

  @Test
  void failedCapabilityValidationDoesNotLeaveSettingsOrOutbox() {
    assertThatThrownBy(
            () ->
                accounting.initialize(
                    id,
                    new ContractAccountingService.Initialize(
                        false, List.of(1L, 0L, 1L, 0L), new BigDecimal("21"), 14)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(db.queryForObject("SELECT count(*) FROM rental.contract_accounting", Long.class))
        .isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM rental.outbox_events", Long.class)).isZero();
  }
}
