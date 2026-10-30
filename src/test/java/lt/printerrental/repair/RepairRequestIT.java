package lt.printerrental.repair;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "OUTBOX_INITIAL_DELAY_MS=3600000",
      "NEON_AUTH_BASE_URL=https://auth.test/auth",
      "NEON_AUTH_JWKS_URL=https://auth.test/jwks"
    })
@AutoConfigureMockMvc
@Testcontainers
class RepairRequestIT {
  @Container
  static final PostgreSQLContainer<?> PG =
      new lt.printerrental.testing.TestPostgresContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add("DB_SCHEMA", () -> "public");
    p.add("spring.datasource.url", PG::getJdbcUrl);
    p.add("spring.datasource.username", PG::getUsername);
    p.add("spring.datasource.password", PG::getPassword);
    p.add("spring.flyway.url", PG::getJdbcUrl);
    p.add("spring.flyway.user", PG::getUsername);
    p.add("spring.flyway.password", PG::getPassword);
  }

  @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
  @MockitoBean lt.printerrental.auth.SigningKeyProvider keys;
  @MockitoBean lt.printerrental.auth.UserIdentityResolver identities;
  @MockitoBean lt.printerrental.events.EventPublisher publisher;
  @MockitoBean lt.printerrental.messaging.OutboxDispatchJob outbox;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate db;
  @Autowired RepairRequestService service;
  @Autowired PlatformTransactionManager transactions;
  private static final String BASE = "/api/repair-requests";

  @BeforeEach
  void clean() {
    db.execute(
        "TRUNCATE contract_terms, contract_accounting, repair_requests, rental_contracts,"
            + " rental_requests, printers, outbox_events, rental_request_locations, printer_locations, customer_locations");
  }

  private UUID activeContract(String owner) {
    var printer = UUID.randomUUID();
    var request = UUID.randomUUID();
    var contract = UUID.randomUUID();
    db.update(
        "INSERT INTO printers(id,serial_number,manufacturer,model,color_capable) VALUES (?,?,"
            + " 'Test','Repair',false)",
        printer,
        printer.toString());
    db.update(
        """
        INSERT INTO rental_requests(id,customer_name,customer_email,start_date,end_date,status,
         created_at,updated_at,printer_id,owner_subject,estimated_monthly_pages,color_printing_required)
        VALUES (?, 'Test','repair@example.com',CURRENT_DATE,CURRENT_DATE+1,'APPROVED',now(),now(),?,?,100,false)
        """,
        request,
        printer,
        owner);
    db.update(
        """
        INSERT INTO rental_contracts(id,rental_request_id,printer_id,customer_name,customer_email,
         start_date,end_date,monthly_fee,included_mono_pages,included_color_pages,mono_page_price,
         color_page_price,currency,status,created_at,initial_mono_reading,initial_color_reading,
         initial_reading_date,activated_at)
        VALUES (?,?,?,'Test','repair@example.com',CURRENT_DATE,CURRENT_DATE+1,10,100,0,0.01,0,'EUR','ACTIVE',now(),0,0,CURRENT_DATE,now())
        """,
        contract,
        request,
        printer);
    return contract;
  }

  private String createBody(UUID contract) throws Exception {
    return mapper.writeValueAsString(
        Map.of("contractId", contract, "description", "Paper feed rollers are broken"));
  }

  private JsonNode create(UUID contract, String owner) throws Exception {
    var result =
        mvc.perform(
                post(BASE)
                    .with(user(owner).roles("CUSTOMER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(contract)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  private void change(
      String id,
      String owner,
      String role,
      String status,
      long version,
      String resolution,
      int expected)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("status", status);
    body.put("version", version);
    if (resolution != null) body.put("resolution", resolution);
    mvc.perform(
            patch(BASE + "/" + id + "/status")
                .with(user(owner).roles(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().is(expected));
  }

  private long count(String table) {
    return db.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  @Test
  void customerOwnershipStaffLifecycleAndVersionGuardsAreEnforced() throws Exception {
    var contract = activeContract("alice");
    var repair = create(contract, "alice");
    var id = repair.get("id").asText();
    assertThat(repair.get("contractId").asText()).isEqualTo(contract.toString());
    assertThat(repair.get("printerId").asText())
        .isEqualTo(
            db.queryForObject(
                "SELECT printer_id::text FROM rental_contracts WHERE id=?",
                String.class,
                contract));
    mvc.perform(get(BASE + "/" + id).with(user("bob").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(get(BASE).with(user("bob").roles("CUSTOMER")))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get(BASE)
                .param("status", "OPEN")
                .param("contractId", contract.toString())
                .with(user("alice").roles("CUSTOMER")))
        .andExpect(jsonPath("$.totalElements").value(1));
    change(id, "alice", "CUSTOMER", "IN_PROGRESS", 0, null, 403);
    change(id, "employee", "EMPLOYEE", "IN_PROGRESS", 0, null, 200);
    change(id, "alice", "CUSTOMER", "CANCELLED", 1, null, 403);
    change(id, "employee", "EMPLOYEE", "COMPLETED", 0, "Replaced rollers", 409);
    change(id, "employee", "EMPLOYEE", "COMPLETED", 1, null, 400);
    change(id, "employee", "EMPLOYEE", "COMPLETED", 1, "Replaced rollers", 200);
    change(id, "admin", "ADMIN", "CANCELLED", 2, null, 409);
    mvc.perform(get(BASE + "/" + id).with(user("alice").roles("CUSTOMER")))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.resolution").value("Replaced rollers"));
    assertThat(count("outbox_events")).isEqualTo(3);
    var payloads =
        db.queryForList("SELECT payload::text AS payload FROM outbox_events", String.class);
    assertThat(payloads)
        .allSatisfy(
            payload -> {
              assertThat(payload)
                  .contains("alice", contract.toString())
                  .doesNotContain("Paper feed rollers", "Replaced rollers");
            });
  }

  @Test
  void inactiveAndUnownedContractsAreRejectedWithoutWrites() throws Exception {
    var id = activeContract("alice");
    db.update(
        "UPDATE rental_contracts SET"
            + " status='DRAFT',initial_mono_reading=NULL,initial_color_reading=NULL,initial_reading_date=NULL,activated_at=NULL"
            + " WHERE id=?",
        id);
    mvc.perform(
            post(BASE)
                .with(user("alice").roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(id)))
        .andExpect(status().isConflict());
    mvc.perform(
            post(BASE)
                .with(user("bob").roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(id)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(BASE)
                .with(user("alice").roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(UUID.randomUUID())))
        .andExpect(status().isNotFound());
    assertThat(count("repair_requests")).isZero();
    assertThat(count("outbox_events")).isZero();
  }

  @Test
  void duplicateOpenRequestsAreRejectedAndOwnerCanCancelBeforeWorkStarts() throws Exception {
    var id = activeContract("alice");
    var first = create(id, "alice");
    mvc.perform(
            post(BASE)
                .with(user("alice").roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(id)))
        .andExpect(status().isConflict());
    change(first.get("id").asText(), "alice", "CUSTOMER", "CANCELLED", 0, null, 200);
    create(id, "alice");
    assertThat(count("repair_requests")).isEqualTo(2);
    assertThat(count("outbox_events")).isEqualTo(3);
  }

  @Test
  void staffCreatesOnBehalfOfActualContractOwner() throws Exception {
    var id = activeContract("alice");
    var result =
        mvc.perform(
                post(BASE)
                    .with(user("employee").roles("EMPLOYEE"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(id)))
            .andExpect(status().isCreated())
            .andReturn();
    var repair = mapper.readTree(result.getResponse().getContentAsString());
    mvc.perform(get(BASE + "/" + repair.get("id").asText()).with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isOk());
    assertThat(db.queryForObject("SELECT owner_subject FROM repair_requests", String.class))
        .isEqualTo("alice");
  }

  @Test
  void rollbackRemovesBothRepairAndOutbox() {
    var contract = activeContract("alice");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", "unused", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              tx -> {
                service.create(new CreateRepairRequest(contract, "Paper feed rollers are broken"));
                assertThat(count("repair_requests")).isEqualTo(1);
                assertThat(count("outbox_events")).isEqualTo(1);
                tx.setRollbackOnly();
              });
    } finally {
      SecurityContextHolder.clearContext();
    }
    assertThat(count("repair_requests")).isZero();
    assertThat(count("outbox_events")).isZero();
  }

  @Test
  void concurrentCreatesProduceExactlyOneOpenRepairAndOneEvent() throws Exception {
    var contract = activeContract("alice");
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> call =
          () -> {
            start.await(5, TimeUnit.SECONDS);
            return mvc.perform(
                    post(BASE)
                        .with(user("alice").roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(contract)))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var one = executor.submit(call);
      var two = executor.submit(call);
      start.countDown();
      assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
      assertThat(count("repair_requests")).isEqualTo(1);
      assertThat(count("outbox_events")).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  private void completeContract(UUID id) {
    db.update(
        "UPDATE rental_contracts SET"
            + " status='COMPLETED',final_mono_reading=0,final_color_reading=0,final_reading_date=CURRENT_DATE,completed_at=now()"
            + " WHERE id=?",
        id);
  }

  @Test
  void concurrentContractCompletionPreventsRepairCreationAfterCompletion() throws Exception {
    var id = activeContract("alice");
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var completion =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          tx -> {
                            completeContract(id);
                            locked.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("Test release timed out");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var creation =
          executor.submit(
              () ->
                  mvc.perform(
                          post(BASE)
                              .with(user("alice").roles("CUSTOMER"))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(createBody(id)))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .until(
              () ->
                  db.queryForObject(
                          "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND"
                              + " query LIKE '%rental_contracts%'",
                          Long.class)
                      > 0);
      release.countDown();
      completion.get(5, TimeUnit.SECONDS);
      assertThat(creation.get(5, TimeUnit.SECONDS)).isEqualTo(409);
      assertThat(count("repair_requests")).isZero();
      assertThat(count("outbox_events")).isZero();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void databaseConstraintsProtectResolutionAndHistoryRemainsReadable() throws Exception {
    var contract = activeContract("alice");
    var repair = create(contract, "alice");
    var id = UUID.fromString(repair.get("id").asText());
    assertThatThrownBy(
            () -> db.update("UPDATE repair_requests SET status='COMPLETED' WHERE id=?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
    completeContract(contract);
    mvc.perform(get(BASE + "/" + id).with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isOk());
    change(id.toString(), "employee", "EMPLOYEE", "IN_PROGRESS", 0, null, 200);
    change(id.toString(), "employee", "EMPLOYEE", "COMPLETED", 1, "Replaced rollers", 200);
  }
}
