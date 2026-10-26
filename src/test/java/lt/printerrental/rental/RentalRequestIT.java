package lt.printerrental.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
@SpringBootTest(
    properties = {
      "OUTBOX_INITIAL_DELAY_MS=3600000",
      "NEON_AUTH_BASE_URL=https://auth.example.test/auth",
      "NEON_AUTH_JWKS_URL=https://auth.example.test/auth/jwks"
    })
@AutoConfigureMockMvc
@Testcontainers
class RentalRequestIT {
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  org.springframework.security.oauth2.jwt.JwtDecoder decoder;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  lt.printerrental.auth.SigningKeyProvider signingKeys;

  private static final String PATH = "/api/rental-requests";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new lt.printerrental.testing.TestPostgresContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("DB_SCHEMA", () -> "public");
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    properties.add("spring.flyway.user", POSTGRES::getUsername);
    properties.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  lt.printerrental.auth.UserIdentityResolver identityResolver;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  lt.printerrental.events.EventPublisher eventPublisher;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired RentalRequestRepository repository;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired PrinterRepository printers;
  @Autowired PrinterAssignmentService assignments;
  @Autowired ContractRepository contracts;
  @Autowired ContractService contractService;
  @Autowired RentalRequestService rentalRequestService;

  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired lt.printerrental.messaging.OutboxBatchPublisher outbox;
  @Autowired lt.printerrental.events.EventRecorder recorder;

  @Test
  void databaseRollbackAlsoRemovesOutboxEvent() {
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    tx.executeWithoutResult(
        status -> {
          adminRequests()
              .create(
                  new CreateRentalRequest(
                      "Rollback",
                      "a@example.com",
                      100,
                      false,
                      LocalDate.now(java.time.ZoneOffset.UTC),
                      LocalDate.now(java.time.ZoneOffset.UTC),
                      null));
          assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class))
              .isEqualTo(1);
          status.setRollbackOnly();
        });
    assertThat(repository.count()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isZero();
  }

  @Test
  void failedBrokerPublishRetriesTheSameDurableEvent() {
    var id = UUID.randomUUID();
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> recorder.record("RentalRequestCreated", id, "alice", java.util.Map.of()));
    org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
        .when(eventPublisher)
        .publish(org.mockito.ArgumentMatchers.any());
    outbox.publishPending();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND attempts=1",
                Long.class))
        .isEqualTo(1);
    org.mockito.Mockito.reset(eventPublisher);
    jdbc.update("UPDATE outbox_events SET next_attempt_at=now()");
    outbox.publishPending();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL AND attempts=2",
                Long.class))
        .isEqualTo(1);
    org.mockito.Mockito.verify(eventPublisher)
        .publish(org.mockito.ArgumentMatchers.argThat(e -> e.aggregateId().equals(id)));
  }

  @BeforeEach
  void cleanDatabase() {
    jdbc.update("DELETE FROM outbox_events");
    contracts.deleteAll();
    repository.deleteAll();
    printers.deleteAll();
  }

  @Test
  @org.springframework.security.test.context.support.WithAnonymousUser
  void anonymousApiIsDenied() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    mvc.perform(get("/health")).andExpect(status().isOk());
  }

  @Test
  void customersAreIsolatedAndCannotManageRentals() throws Exception {
    var alice =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("alice")
            .roles("CUSTOMER");
    var bob =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("bob")
            .roles("CUSTOMER");
    var response =
        mvc.perform(post(PATH).with(alice).contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isCreated())
            .andReturn();
    var id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(get(PATH + "/" + id).with(alice)).andExpect(status().isOk());
    mvc.perform(get(PATH + "/" + id).with(bob)).andExpect(status().isForbidden());
    mvc.perform(get(PATH).with(bob)).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get(PATH).with(alice)).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            patch(PATH + "/" + id + "/status")
                .with(alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_REVIEW\",\"version\":0}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/printers").with(alice).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/users").with(alice)).andExpect(status().isForbidden());
  }

  @Test
  void contractsInheritRequestOwnershipAndStaffCanReadAll() throws Exception {
    var contract = todayContract(false);
    jdbc.update(
        "UPDATE rental_requests SET owner_subject='alice' WHERE id=?",
        contract.getRentalRequestId());
    var alice =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("alice")
            .roles("CUSTOMER");
    var bob =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("bob")
            .roles("CUSTOMER");
    var employee =
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user("employee")
            .roles("EMPLOYEE");
    mvc.perform(get("/api/contracts/" + contract.getId()).with(alice)).andExpect(status().isOk());
    mvc.perform(get("/api/contracts/" + contract.getId()).with(bob))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/contracts").with(bob)).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/api/contracts").with(alice)).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/api/contracts/" + contract.getId()).with(employee))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/users").with(employee)).andExpect(status().isForbidden());
    jdbc.update(
        "UPDATE rental_requests SET owner_subject=NULL WHERE id=?", contract.getRentalRequestId());
    mvc.perform(get("/api/contracts/" + contract.getId()).with(alice))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/contracts/" + contract.getId()).with(employee))
        .andExpect(status().isOk());
  }

  private void adminContext() {
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "test-admin",
                "",
                java.util.List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_ADMIN"))));
  }

  private ContractService adminContracts() {
    adminContext();
    return contractService;
  }

  private RentalRequestService adminRequests() {
    adminContext();
    return rentalRequestService;
  }

  private String approvedRequest(boolean color) throws Exception {
    return approvedRequest(color, LocalDate.now(java.time.ZoneOffset.UTC).plusDays(5));
  }

  private String approvedRequest(boolean color, LocalDate start) throws Exception {
    var data = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(body());
    data.put("colorPrintingRequired", color);
    data.put("startDate", start.toString());
    data.put("endDate", start.plusDays(2).toString());
    var response =
        mvc.perform(
                post(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(data)))
            .andExpect(status().isCreated())
            .andReturn();
    var id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
    change(id, "IN_REVIEW", 0, 200);
    assign(id, printer(color), 1, 200);
    change(id, "APPROVED", 2, 200);
    return id;
  }

  private CreateContract contractInput(String id) {
    return new CreateContract(
        UUID.fromString(id),
        3L,
        new java.math.BigDecimal("25.50"),
        1000,
        100,
        new java.math.BigDecimal("0.0125"),
        new java.math.BigDecimal("0.0850"));
  }

  private RentalContract todayContract(boolean color) throws Exception {
    var id = approvedRequest(color, LocalDate.now(java.time.ZoneOffset.UTC));
    var input = contractInput(id);
    if (!color) {
      input =
          new CreateContract(
              input.rentalRequestId(),
              input.requestVersion(),
              input.monthlyFee(),
              1000,
              0,
              input.monoPagePrice(),
              java.math.BigDecimal.ZERO);
    }
    return adminContracts().create(input);
  }

  private void activate(RentalContract contract, ActivateContract input, int expected)
      throws Exception {
    mvc.perform(
            post("/api/contracts/" + contract.getId() + "/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input)))
        .andExpect(status().is(expected));
  }

  private RentalContract activeContract(boolean color) throws Exception {
    var contract = todayContract(color);
    return adminContracts()
        .activate(
            contract.getId(),
            new ActivateContract(0L, 100L, color ? 20L : 0L, contract.getStartDate()));
  }

  private void complete(RentalContract contract, CompleteContract input, int expected)
      throws Exception {
    mvc.perform(
            post("/api/contracts/" + contract.getId() + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input)))
        .andExpect(status().is(expected));
  }

  @Test
  void completionPersistsFinalReadingsAndReleasesReservationAtomically() throws Exception {
    var contract = activeContract(true);
    var next = create().get("id").asText();
    jdbc.update(
        "UPDATE rental_requests SET start_date = ?, end_date = ? WHERE id = ?",
        contract.getStartDate(),
        contract.getEndDate(),
        UUID.fromString(next));
    change(next, "IN_REVIEW", 0, 200);
    assign(next, contract.getPrinterId(), 1, 409);
    var before = repository.findById(contract.getRentalRequestId()).orElseThrow();

    mvc.perform(
            post("/api/contracts/" + contract.getId() + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new CompleteContract(1L, 250L, 50L, contract.getStartDate()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.finalMonoReading").value(250))
        .andExpect(jsonPath("$.finalColorReading").value(50))
        .andExpect(jsonPath("$.completedAt").isNotEmpty())
        .andExpect(jsonPath("$.totalMonoPages").value(150))
        .andExpect(jsonPath("$.totalColorPages").value(30));
    var stored = contracts.findById(contract.getId()).orElseThrow();
    assertThat(stored.getFinalReadingDate()).isEqualTo(contract.getStartDate());
    assertThat(stored.getInitialMonoReading()).isEqualTo(100L);
    assertThat(stored.getActivatedAt()).isEqualTo(contract.getActivatedAt());
    var request = repository.findById(contract.getRentalRequestId()).orElseThrow();
    assertThat(request.getStatus()).isEqualTo(RentalStatus.COMPLETED);
    assertThat(request.getVersion()).isEqualTo(before.getVersion() + 1);
    assertThat(request.getUpdatedAt()).isEqualTo(stored.getCompletedAt());
    assertThat(request.getPrinterId()).isEqualTo(contract.getPrinterId());
    assertThat(request.getEndDate()).isEqualTo(contract.getEndDate());
    assign(next, contract.getPrinterId(), 1, 200);
    mvc.perform(get("/api/contracts/" + contract.getId()))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.totalMonoPages").value(150));
    mvc.perform(get("/api/contracts?size=1"))
        .andExpect(jsonPath("$.content[0].finalMonoReading").value(250));
  }

  @Test
  void completionRejectsDraftStaleVersionDuplicateAndMissingContract() throws Exception {
    var draft = todayContract(true);
    complete(draft, new CompleteContract(0L, 100L, 20L, draft.getStartDate()), 409);
    var active =
        adminContracts()
            .activate(draft.getId(), new ActivateContract(0L, 100L, 20L, draft.getStartDate()));
    complete(active, new CompleteContract(0L, 100L, 20L, active.getStartDate()), 409);
    complete(active, new CompleteContract(1L, 100L, 20L, active.getStartDate()), 200);
    complete(active, new CompleteContract(1L, 200L, 30L, active.getStartDate()), 409);
    complete(active, new CompleteContract(2L, 200L, 30L, active.getStartDate()), 409);
    var stored = contracts.findById(active.getId()).orElseThrow();
    assertThat(stored.getFinalMonoReading()).isEqualTo(100L);
    assertThat(stored.getTotalMonoPages()).isZero();
    assertThat(stored.getTotalColorPages()).isZero();
    assertThat(stored.getVersion()).isEqualTo(2L);
    mvc.perform(
            post("/api/contracts/" + UUID.randomUUID() + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new CompleteContract(1L, 100L, 20L, active.getStartDate()))))
        .andExpect(status().isNotFound());
    activate(active, new ActivateContract(2L, 100L, 20L, active.getStartDate()), 409);
  }

  @Test
  void completionRejectsDecreasedReadingsAndInvalidDatesWithoutChangingReservation()
      throws Exception {
    var contract = activeContract(true);
    var today = contract.getStartDate();
    for (var input :
        java.util.List.of(
            new CompleteContract(1L, 99L, 20L, today),
            new CompleteContract(1L, 100L, 19L, today),
            new CompleteContract(1L, 100L, 20L, today.minusDays(1)),
            new CompleteContract(1L, 100L, 20L, today.plusDays(1)),
            new CompleteContract(1L, 100L, 20L, contract.getEndDate().plusDays(1)))) {
      complete(contract, input, 400);
    }
    var stored = contracts.findById(contract.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ContractStatus.ACTIVE);
    assertThat(stored.getVersion()).isEqualTo(1L);
    assertThat(stored.getFinalMonoReading()).isNull();
    assertThat(stored.getCompletedAt()).isNull();
    assertThat(stored.getTotalMonoPages()).isNull();
    assertThat(repository.findById(contract.getRentalRequestId()).orElseThrow().getStatus())
        .isEqualTo(RentalStatus.APPROVED);
    change(contract.getRentalRequestId().toString(), "CANCELLED", 3, 409);
  }

  @Test
  void monochromeCompletionRequiresZeroColorAndAllowsMaximumLongCounter() throws Exception {
    var contract = activeContract(false);
    complete(contract, new CompleteContract(1L, 100L, 1L, contract.getStartDate()), 400);
    complete(contract, new CompleteContract(1L, Long.MAX_VALUE, 0L, contract.getStartDate()), 200);
    var stored = contracts.findById(contract.getId()).orElseThrow();
    assertThat(stored.getTotalMonoPages()).isEqualTo(Long.MAX_VALUE - 100);
    assertThat(stored.getTotalColorPages()).isZero();
  }

  @Test
  void completionCanRecordPeriodEndReadingsAfterThePeriodHasExpired() throws Exception {
    var contract = activeContract(true);
    var yesterday = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
    var start = yesterday.minusDays(2);
    jdbc.update(
        "UPDATE rental_contracts SET start_date = ?, end_date = ?, initial_reading_date = ? WHERE"
            + " id = ?",
        start,
        yesterday,
        start,
        contract.getId());
    jdbc.update(
        "UPDATE rental_requests SET start_date = ?, end_date = ? WHERE id = ?",
        start,
        yesterday,
        contract.getRentalRequestId());
    complete(contract, new CompleteContract(1L, 101L, 21L, yesterday.plusDays(1)), 400);
    complete(contract, new CompleteContract(1L, 101L, 21L, yesterday), 200);
    assertThat(contracts.findById(contract.getId()).orElseThrow().getFinalReadingDate())
        .isEqualTo(yesterday);
  }

  @Test
  void completionRejectsMismatchedRequestSnapshot() throws Exception {
    var contract = activeContract(true);
    jdbc.update(
        "UPDATE rental_requests SET printer_id = ? WHERE id = ?",
        printer(true),
        contract.getRentalRequestId());
    complete(contract, new CompleteContract(1L, 100L, 20L, contract.getStartDate()), 409);
    assertThat(contracts.findById(contract.getId()).orElseThrow().getStatus())
        .isEqualTo(ContractStatus.ACTIVE);
    assertThat(repository.findById(contract.getRentalRequestId()).orElseThrow().getStatus())
        .isEqualTo(RentalStatus.APPROVED);
  }

  @Test
  void concurrentCompletionRecordsOnlyOneFinalReadingAndOneRequestTransition() throws Exception {
    var contract = activeContract(true);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor =
        new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(
            java.util.concurrent.Executors.newFixedThreadPool(2))) {
      var jobs =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  i ->
                      executor.submit(
                          () -> {
                            start.await();
                            try {
                              adminContracts()
                                  .complete(
                                      contract.getId(),
                                      new CompleteContract(
                                          1L, 150L + i, 20L, contract.getStartDate()));
                              return "completed";
                            } catch (RentalConflictException
                                | org.springframework.dao.OptimisticLockingFailureException
                                    expected) {
                              return "conflict";
                            }
                          }))
              .toList();
      start.countDown();
      assertThat(
              java.util.List.of(
                  jobs.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                  jobs.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("completed", "conflict");
    }
    var stored = contracts.findById(contract.getId()).orElseThrow();
    assertThat(stored.getFinalMonoReading()).isIn(150L, 151L);
    assertThat(stored.getVersion()).isEqualTo(2L);
    var request = repository.findById(contract.getRentalRequestId()).orElseThrow();
    assertThat(request.getStatus()).isEqualTo(RentalStatus.COMPLETED);
    assertThat(request.getVersion()).isEqualTo(4L);
  }

  @Test
  void completionAndCancellationKeepContractAndRequestConsistent() throws Exception {
    var contract = activeContract(true);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor =
        new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(
            java.util.concurrent.Executors.newFixedThreadPool(2))) {
      var completion =
          executor.submit(
              () -> {
                start.await();
                return adminContracts()
                    .complete(
                        contract.getId(),
                        new CompleteContract(1L, 100L, 20L, contract.getStartDate()));
              });
      var cancellation =
          executor.submit(
              () -> {
                start.await();
                try {
                  adminRequests()
                      .changeStatus(
                          contract.getRentalRequestId(),
                          new ChangeRentalStatus(RentalStatus.CANCELLED, 3L));
                  return "cancelled";
                } catch (RentalConflictException expected) {
                  return "conflict";
                }
              });
      start.countDown();
      assertThat(completion.get(20, java.util.concurrent.TimeUnit.SECONDS).getStatus())
          .isEqualTo(ContractStatus.COMPLETED);
      assertThat(cancellation.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("conflict");
    }
    assertThat(repository.findById(contract.getRentalRequestId()).orElseThrow().getStatus())
        .isEqualTo(RentalStatus.COMPLETED);
  }

  @Test
  void databaseRejectsMissingDecreasedOrOutOfPeriodFinalReadings() throws Exception {
    var contract = activeContract(true);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE rental_contracts SET status = 'COMPLETED' WHERE id = ?",
                    contract.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE rental_contracts SET final_mono_reading = 100 WHERE id = ?",
                    contract.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    var sql =
        """
        UPDATE rental_contracts SET status = 'COMPLETED', final_mono_reading = ?, final_color_reading = ?,
        final_reading_date = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?
        """;
    assertThatThrownBy(() -> jdbc.update(sql, 99, 20, contract.getStartDate(), contract.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(sql, 100, 19, contract.getStartDate(), contract.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update(sql, 100, 20, contract.getStartDate().minusDays(1), contract.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update(sql, 100, 20, contract.getEndDate().plusDays(1), contract.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void failureToUpdateRequestRollsBackContractCompletion() throws Exception {
    var contract = activeContract(true);
    // Force a database-side failure after both managed entities have been changed.
    jdbc.execute(
        "ALTER TABLE rental_requests ADD CONSTRAINT test_block_completion CHECK (status <>"
            + " 'COMPLETED')");
    try {
      assertThatThrownBy(
              () ->
                  adminContracts()
                      .complete(
                          contract.getId(),
                          new CompleteContract(1L, 100L, 20L, contract.getStartDate())))
          .isInstanceOf(DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("ALTER TABLE rental_requests DROP CONSTRAINT test_block_completion");
    }
    var stored = contracts.findById(contract.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ContractStatus.ACTIVE);
    assertThat(stored.getVersion()).isEqualTo(1L);
    assertThat(stored.getFinalMonoReading()).isNull();
    assertThat(repository.findById(contract.getRentalRequestId()).orElseThrow().getStatus())
        .isEqualTo(RentalStatus.APPROVED);
  }

  @Test
  void completionMigrationPreservesExistingDraftAndActiveContracts() throws Exception {
    var draft = todayContract(true);
    var active = activeContract(true);
    var config =
        org.flywaydb.core.Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("completion_upgrade")
            .locations("classpath:db/migration");
    config.target("5").load().migrate();
    for (String table : new String[] {"printers", "rental_requests", "rental_contracts"}) {
      var columns =
          String.join(
              ", ",
              jdbc.queryForList(
                  """
                  SELECT column_name FROM information_schema.columns
                  WHERE table_schema = 'completion_upgrade' AND table_name = ? ORDER BY ordinal_position
                  """,
                  String.class,
                  table));
      jdbc.update(
          "INSERT INTO completion_upgrade."
              + table
              + " ("
              + columns
              + ") SELECT "
              + columns
              + " FROM public."
              + table);
    }
    config.target("latest").load().migrate();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM completion_upgrade.rental_contracts WHERE id = ?",
                String.class,
                draft.getId()))
        .isEqualTo("DRAFT");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM completion_upgrade.rental_contracts WHERE id = ?",
                String.class,
                active.getId()))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT initial_mono_reading FROM completion_upgrade.rental_contracts WHERE id = ?",
                Long.class,
                active.getId()))
        .isEqualTo(100L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM completion_upgrade.rental_contracts WHERE final_mono_reading"
                    + " IS NULL AND completed_at IS NULL",
                Long.class))
        .isEqualTo(2L);
  }

  @Test
  void activationStoresInitialReadingsAndProtectsReservation() throws Exception {
    var contract = todayContract(true);
    var input = new ActivateContract(0L, 50000L, 12000L, contract.getStartDate());
    activate(contract, input, 200);
    var stored = contracts.findById(contract.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ContractStatus.ACTIVE);
    assertThat(stored.getInitialMonoReading()).isEqualTo(50000L);
    assertThat(stored.getInitialColorReading()).isEqualTo(12000L);
    assertThat(stored.getInitialReadingDate()).isEqualTo(contract.getStartDate());
    assertThat(stored.getActivatedAt()).isNotNull();
    assertThat(stored.getVersion()).isEqualTo(1);
    activate(contract, input, 409);
    activate(contract, new ActivateContract(1L, 60000L, 13000L, contract.getStartDate()), 409);
    change(contract.getRentalRequestId().toString(), "CANCELLED", 3, 409);
    change(contract.getRentalRequestId().toString(), "COMPLETED", 3, 409);
    assertThat(contracts.findById(contract.getId()).orElseThrow().getInitialMonoReading())
        .isEqualTo(50000L);
  }

  @Test
  void activationRejectsCancelledRequestFutureExpiredDatesAndStaleVersion() throws Exception {
    var contract = todayContract(true);
    activate(contract, new ActivateContract(1L, 1L, 0L, contract.getStartDate()), 409);
    activate(contract, new ActivateContract(0L, 1L, 0L, contract.getStartDate().plusDays(1)), 400);
    change(contract.getRentalRequestId().toString(), "CANCELLED", 3, 200);
    activate(contract, new ActivateContract(0L, 1L, 0L, contract.getStartDate()), 409);
    var futureInput = contractInput(approvedRequest(true));
    var future = adminContracts().create(futureInput);
    activate(future, new ActivateContract(0L, 1L, 0L, future.getStartDate()), 409);
    var expired = todayContract(true);
    var yesterday = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
    jdbc.update(
        "UPDATE rental_contracts SET start_date = ?, end_date = ? WHERE id = ?",
        yesterday,
        yesterday,
        expired.getId());
    jdbc.update(
        "UPDATE rental_requests SET start_date = ?, end_date = ? WHERE id = ?",
        yesterday,
        yesterday,
        expired.getRentalRequestId());
    activate(expired, new ActivateContract(0L, 1L, 0L, yesterday), 409);
    assertThat(contracts.findById(expired.getId()).orElseThrow().getStatus())
        .isEqualTo(ContractStatus.DRAFT);
  }

  @Test
  void activationRejectsInvalidReadingsAndSupportsZeroMonochromeBaseline() throws Exception {
    var contract = todayContract(false);
    var valid =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.valueToTree(new ActivateContract(0L, 0L, 0L, contract.getStartDate()));
    for (String field :
        new String[] {
          "version", "initialMonoReading", "initialColorReading", "initialReadingDate"
        }) {
      var invalid = valid.deepCopy();
      invalid.putNull(field);
      mvc.perform(
              post("/api/contracts/" + contract.getId() + "/activate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid.toString()))
          .andExpect(status().isBadRequest());
    }
    for (String field : new String[] {"version", "initialMonoReading", "initialColorReading"}) {
      for (java.math.BigDecimal value :
          new java.math.BigDecimal[] {
            new java.math.BigDecimal("-1"),
            new java.math.BigDecimal("0.5"),
            new java.math.BigDecimal("9223372036854775808")
          }) {
        var invalid = valid.deepCopy();
        invalid.put(field, value);
        mvc.perform(
                post("/api/contracts/" + contract.getId() + "/activate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(invalid.toString()))
            .andExpect(status().isBadRequest());
      }
    }
    activate(contract, new ActivateContract(0L, 0L, 1L, contract.getStartDate()), 400);
    mvc.perform(
            post("/api/contracts/" + UUID.randomUUID() + "/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(valid.toString()))
        .andExpect(status().isNotFound());
    activate(contract, new ActivateContract(0L, 0L, 0L, contract.getStartDate()), 200);
  }

  @Test
  void activationRechecksPrinterOverlapAndSnapshot() throws Exception {
    var contract = todayContract(true);
    var other = approvedRequest(true, contract.getStartDate());
    // Simulate conflicting historical data: normal assignment API prevents this.
    jdbc.update(
        "UPDATE rental_requests SET printer_id = ? WHERE id = ?",
        contract.getPrinterId(),
        UUID.fromString(other));
    activate(contract, new ActivateContract(0L, 100L, 0L, contract.getStartDate()), 409);
    change(other, "CANCELLED", 3, 200);
    jdbc.update(
        "UPDATE rental_requests SET printer_id = ? WHERE id = ?",
        printer(true),
        contract.getRentalRequestId());
    activate(contract, new ActivateContract(0L, 100L, 0L, contract.getStartDate()), 409);
    assertThat(contracts.findById(contract.getId()).orElseThrow().getInitialMonoReading()).isNull();
  }

  @Test
  void concurrentActivationSavesOnlyOneBaseline() throws Exception {
    var contract = todayContract(true);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor =
        new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(
            java.util.concurrent.Executors.newFixedThreadPool(2))) {
      var jobs =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  i ->
                      executor.submit(
                          () -> {
                            start.await();
                            try {
                              adminContracts()
                                  .activate(
                                      contract.getId(),
                                      new ActivateContract(
                                          0L, 100L + i, 0L, contract.getStartDate()));
                              return "active";
                            } catch (RentalConflictException
                                | org.springframework.dao.OptimisticLockingFailureException
                                    expected) {
                              return "conflict";
                            }
                          }))
              .toList();
      start.countDown();
      assertThat(
              java.util.List.of(
                  jobs.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                  jobs.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("active", "conflict");
    }
    var stored = contracts.findById(contract.getId()).orElseThrow();
    assertThat(stored.getVersion()).isEqualTo(1);
    assertThat(stored.getInitialMonoReading()).isIn(100L, 101L);
  }

  @Test
  void databaseRejectsActiveContractWithoutBaseline() throws Exception {
    var contract = todayContract(true);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE rental_contracts SET status = 'ACTIVE' WHERE id = ?", contract.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void createsDraftWithExactTariffsAndRequestSnapshot() throws Exception {
    var id = approvedRequest(true);
    var response =
        mvc.perform(
                post("/api/contracts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(contractInput(id))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.customerEmail").value("jonas@example.com"))
            .andExpect(jsonPath("$.rentalRequestId").value(id))
            .andExpect(jsonPath("$.version").value(0))
            .andReturn();
    var contractId =
        mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
    assertThat(response.getResponse().getHeader("Location"))
        .isEqualTo("/api/contracts/" + contractId);
    var stored = contracts.findById(UUID.fromString(contractId)).orElseThrow();
    assertThat(stored.getMonthlyFee()).isEqualByComparingTo("25.50");
    assertThat(stored.getMonoPagePrice()).isEqualByComparingTo("0.0125");
    assertThat(stored.getColorPagePrice()).isEqualByComparingTo("0.0850");
    assertThat(stored.getPrinterId())
        .isEqualTo(repository.findById(UUID.fromString(id)).orElseThrow().getPrinterId());
    mvc.perform(get("/api/contracts/" + contractId)).andExpect(status().isOk());
    mvc.perform(get("/api/contracts?size=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    // Drafts retain their snapshot but do not prevent request cancellation.
    change(id, "CANCELLED", 3, 200);
    assertThat(contracts.findById(stored.getId()).orElseThrow().getCustomerName())
        .isEqualTo("Jonas Jonaitis");
  }

  @Test
  void contractRejectsInvalidStateVersionDuplicateAndUnknownIds() throws Exception {
    var pending = create().get("id").asText();
    var input =
        (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(contractInput(pending));
    input.put("requestVersion", 0);
    mvc.perform(
            post("/api/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isConflict());
    var id = approvedRequest(true);
    input.put("rentalRequestId", id);
    mvc.perform(
            post("/api/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isConflict());
    input.put("requestVersion", 3);
    mvc.perform(
            post("/api/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isConflict());
    input.put("rentalRequestId", UUID.randomUUID().toString());
    mvc.perform(
            post("/api/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/contracts/" + UUID.randomUUID())).andExpect(status().isNotFound());
    mvc.perform(get("/api/contracts?size=101")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/contracts?page=-1")).andExpect(status().isBadRequest());
  }

  @Test
  void contractValidatesMoneyAndRejectsClientSnapshotOverrides() throws Exception {
    var input =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.valueToTree(contractInput(approvedRequest(true)));
    for (String field :
        new String[] {
          "monthlyFee",
          "includedMonoPages",
          "includedColorPages",
          "monoPagePrice",
          "colorPagePrice",
          "requestVersion",
          "rentalRequestId"
        }) {
      var invalid = input.deepCopy();
      invalid.putNull(field);
      mvc.perform(
              post("/api/contracts")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid.toString()))
          .andExpect(status().isBadRequest());
    }
    for (String field :
        new String[] {
          "monthlyFee",
          "includedMonoPages",
          "includedColorPages",
          "monoPagePrice",
          "colorPagePrice",
          "requestVersion"
        }) {
      var invalid = input.deepCopy();
      invalid.put(field, -1);
      mvc.perform(
              post("/api/contracts")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid.toString()))
          .andExpect(status().isBadRequest());
    }
    for (String field :
        new String[] {"monthlyFee", "monoPagePrice", "colorPagePrice", "includedMonoPages"}) {
      var invalid = input.deepCopy();
      invalid.put(field, new java.math.BigDecimal("0.00001"));
      mvc.perform(
              post("/api/contracts")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid.toString()))
          .andExpect(status().isBadRequest());
      invalid.put(field, new java.math.BigDecimal("100000000000"));
      mvc.perform(
              post("/api/contracts")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid.toString()))
          .andExpect(status().isBadRequest());
    }
    for (String field :
        new String[] {"customerName", "printerId", "status", "currency", "startDate"}) {
      var invalid = input.deepCopy();
      invalid.put(field, "override");
      mvc.perform(
              post("/api/contracts")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid.toString()))
          .andExpect(status().isBadRequest());
    }
    assertThat(contracts.count()).isZero();
  }

  @Test
  void monochromeContractRequiresZeroColorTariffsAndAllowsZeroFee() throws Exception {
    var input =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.valueToTree(contractInput(approvedRequest(false)));
    mvc.perform(
            post("/api/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isBadRequest());
    input.put("includedColorPages", 0);
    input.put("colorPagePrice", 0);
    input.put("monthlyFee", 0);
    mvc.perform(
            post("/api/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isCreated());
  }

  @Test
  void concurrentContractCreationCreatesOnlyOneDraft() throws Exception {
    var input = contractInput(approvedRequest(true));
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor =
        new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(
            java.util.concurrent.Executors.newFixedThreadPool(2))) {
      var jobs =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  i ->
                      executor.submit(
                          () -> {
                            start.await();
                            try {
                              adminContracts().create(input);
                              return "created";
                            } catch (RentalConflictException expected) {
                              return "conflict";
                            }
                          }))
              .toList();
      start.countDown();
      assertThat(
              java.util.List.of(
                  jobs.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                  jobs.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("created", "conflict");
    }
    assertThat(contracts.count()).isEqualTo(1);
  }

  private UUID printer(boolean color) {
    return printers
        .saveAndFlush(
            new Printer(
                new CreatePrinter(UUID.randomUUID().toString(), "Brother", "Test model", color)))
        .getId();
  }

  private void assign(String id, UUID printerId, long version, int expected) throws Exception {
    mvc.perform(
            patch(PATH + "/" + id + "/printer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new AssignPrinter(printerId, version))))
        .andExpect(status().is(expected));
  }

  private String body() throws Exception {
    var start = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(5);
    return mapper.writeValueAsString(
        new CreateRentalRequest(
            "Jonas Jonaitis",
            "jonas@example.com",
            1500,
            true,
            start,
            start.plusDays(2),
            "Reikia savaitgaliui"));
  }

  private JsonNode create() throws Exception {
    var result =
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.version").value(0))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  private void change(String id, String status, long version, int expectedStatus) throws Exception {
    mvc.perform(
            patch(PATH + "/" + id + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\",\"version\":" + version + "}"))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void flywayAppliesSchemaAndHibernateValidatesIt() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
                Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM rental_requests", Long.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void persistsReadsApprovesAndCompletesRequest() throws Exception {
    var created = create();
    var id = created.get("id").asText();
    mvc.perform(get(PATH + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerEmail").value("jonas@example.com"))
        .andExpect(jsonPath("$.estimatedMonthlyPages").value(1500))
        .andExpect(jsonPath("$.colorPrintingRequired").value(true))
        .andExpect(jsonPath("$.resourceId").doesNotExist());
    change(id, "APPROVED", 0, 409);
    change(id, "IN_REVIEW", 0, 200);
    mvc.perform(get(PATH + "?status=IN_REVIEW")).andExpect(jsonPath("$.totalElements").value(1));
    change(id, "APPROVED", 1, 409);
    assign(id, printer(true), 1, 200);
    change(id, "APPROVED", 2, 200);
    mvc.perform(get(PATH + "/" + id))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.version").value(3));
    change(id, "COMPLETED", 3, 200);
    change(id, "CANCELLED", 4, 409);
    var stored = repository.findById(UUID.fromString(id)).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(RentalStatus.COMPLETED);
    assertThat(stored.getVersion()).isEqualTo(4);
    assertThat(stored.getCreatedAt()).isEqualTo(Instant.parse(created.get("createdAt").asText()));
    assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(stored.getCreatedAt());
  }

  @Test
  void migrationPreservesExistingRequestsAndAssignments() {
    var config =
        org.flywaydb.core.Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("upgrade_test")
            .locations("classpath:db/migration");
    config.target("1").load().migrate();
    var id = UUID.randomUUID();
    var printerId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO upgrade_test.rental_requests
        (id, resource_id, customer_name, customer_email, start_date, end_date,
         status, created_at, updated_at, version)
        VALUES (?, ?, 'Existing client', 'existing@example.com', CURRENT_DATE,
                CURRENT_DATE, 'APPROVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 3)
        """,
        id,
        printerId);
    config.target("latest").load().migrate();
    var row = jdbc.queryForMap("SELECT * FROM upgrade_test.rental_requests WHERE id = ?", id);
    assertThat(row.get("resource_id")).isEqualTo(printerId);
    assertThat(row.get("status")).isEqualTo("APPROVED");
    assertThat(row.get("version")).isEqualTo(3L);
    assertThat(row.get("estimated_monthly_pages")).isNull();
    assertThat(row.get("color_printing_required")).isNull();
    assertThat(row.get("printer_id")).isNull();
  }

  @Test
  void catalogValidatesNormalizesAndRejectsDuplicateSerial() throws Exception {
    String json =
        """
        {"serialNumber":" abc-123 ","manufacturer":"Brother","model":"MFC","colorCapable":true}
        """;
    var response =
        mvc.perform(post("/api/printers").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.serialNumber").value("ABC-123"))
            .andReturn();
    var id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(get("/api/printers/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.colorCapable").value(true));
    mvc.perform(get("/api/printers?size=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            post("/api/printers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.replace(" abc-123 ", "ABC-123")))
        .andExpect(status().isConflict());
    for (String invalid :
        new String[] {
          json.replace(" abc-123 ", " "),
          json.replace("true", "null"),
          json.replace("Brother", "x".repeat(101))
        }) {
      mvc.perform(post("/api/printers").contentType(MediaType.APPLICATION_JSON).content(invalid))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(get("/api/printers/" + UUID.randomUUID())).andExpect(status().isNotFound());
    mvc.perform(get("/api/printers?size=101")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/printers?page=-1")).andExpect(status().isBadRequest());
  }

  @Test
  void assignmentChecksStatusVersionColorAndMissingPrinter() throws Exception {
    var id = create().get("id").asText();
    var color = printer(true);
    assign(id, color, 0, 409);
    change(id, "IN_REVIEW", 0, 200);
    assign(id, color, 0, 409);
    assign(id, printer(false), 1, 409);
    assign(id, UUID.randomUUID(), 1, 404);
    assign(UUID.randomUUID().toString(), color, 0, 404);
    assign(id, color, 1, 200);
    mvc.perform(get(PATH + "/" + id))
        .andExpect(jsonPath("$.printerId").value(color.toString()))
        .andExpect(jsonPath("$.version").value(2));
    change(id, "APPROVED", 2, 200);
    assign(id, printer(true), 3, 409);
  }

  @Test
  void blocksOverlapAndReleasesPrinterAfterCancellation() throws Exception {
    var first = create().get("id").asText();
    var second = create().get("id").asText();
    var printerId = printer(true);
    change(first, "IN_REVIEW", 0, 200);
    change(second, "IN_REVIEW", 0, 200);
    assign(first, printerId, 1, 200);
    assign(second, printerId, 1, 409);
    change(first, "APPROVED", 2, 200);
    assign(second, printerId, 1, 409);
    change(first, "CANCELLED", 3, 200);
    assign(second, printerId, 1, 200);
  }

  @Test
  void reassigningReleasesOldPrinterAndChecksInclusiveDateBoundaries() throws Exception {
    var first = create().get("id").asText();
    var oldPrinter = printer(true);
    change(first, "IN_REVIEW", 0, 200);
    assign(first, oldPrinter, 1, 200);
    assign(first, printer(true), 2, 200);
    var second = create().get("id").asText();
    change(second, "IN_REVIEW", 0, 200);
    assign(second, oldPrinter, 1, 200);
    var data = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(body());
    var boundary = LocalDate.parse(data.get("endDate").asText());
    for (int gap : new int[] {0, 1}) {
      data.put("startDate", boundary.plusDays(gap).toString());
      data.put("endDate", boundary.plusDays(gap + 2).toString());
      var response =
          mvc.perform(
                  post(PATH)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(mapper.writeValueAsString(data)))
              .andExpect(status().isCreated())
              .andReturn();
      var id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asText();
      change(id, "IN_REVIEW", 0, 200);
      assign(id, oldPrinter, 1, gap == 0 ? 409 : 200);
    }
  }

  @Test
  void concurrentAssignmentsCannotDoubleBookPrinter() throws Exception {
    var first = UUID.fromString(create().get("id").asText());
    var second = UUID.fromString(create().get("id").asText());
    change(first.toString(), "IN_REVIEW", 0, 200);
    change(second.toString(), "IN_REVIEW", 0, 200);
    var printerId = printer(true);
    adminContext(); // The workers inherit staff identity after MockMvc clears the caller context.
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor =
        new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(
            java.util.concurrent.Executors.newFixedThreadPool(2))) {
      var jobs =
          java.util.stream.Stream.of(first, second)
              .map(
                  id ->
                      executor.submit(
                          () -> {
                            start.await();
                            try {
                              assignments.assign(id, new AssignPrinter(printerId, 1L));
                              return "assigned";
                            } catch (RentalConflictException expected) {
                              return "conflict";
                            }
                          }))
              .toList();
      start.countDown();
      assertThat(
              java.util.List.of(
                  jobs.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                  jobs.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("assigned", "conflict");
    }
  }

  @Test
  void rejectsStaleVersionAndInvalidTransitionWithoutPersistingThem() throws Exception {
    var id = create().get("id").asText();
    change(id, "COMPLETED", 0, 409);
    change(id, "IN_REVIEW", 0, 200);
    change(id, "CANCELLED", 0, 409);
    mvc.perform(get(PATH + "/" + id))
        .andExpect(jsonPath("$.status").value("IN_REVIEW"))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void supportsFilteringAndPagination() throws Exception {
    var first = create().get("id").asText();
    create();
    change(first, "REJECTED", 0, 200);
    mvc.perform(get(PATH + "?status=PENDING&page=0&size=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].status").value("PENDING"))
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get(PATH + "?page=1&size=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(2));
    mvc.perform(get(PATH + "?page=2&size=1")).andExpect(jsonPath("$.content").isEmpty());
  }

  @Test
  void rejectsInvalidDatesWithoutPersisting() throws Exception {
    var data = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(body());
    data.put("endDate", LocalDate.now(java.time.ZoneOffset.UTC).toString());
    mvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(data)))
        .andExpect(status().isBadRequest());
    data.put("startDate", LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString());
    mvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(data)))
        .andExpect(status().isBadRequest());
    assertThat(repository.count()).isZero();
  }

  @Test
  void returnsNotFoundForReadAndStatusChange() throws Exception {
    var id = UUID.randomUUID().toString();
    mvc.perform(get(PATH + "/" + id)).andExpect(status().isNotFound());
    change(id, "APPROVED", 0, 404);
  }

  @Test
  void postgresEnforcesDateAndStatusConstraints() throws Exception {
    var id = UUID.fromString(create().get("id").asText());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE rental_requests SET end_date = start_date - 1 WHERE id = ?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE rental_requests SET status = 'INVALID' WHERE id = ?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE rental_requests SET estimated_monthly_pages = 0 WHERE id = ?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void optimisticLockRejectsConcurrentDatabaseUpdate() throws Exception {
    var id = UUID.fromString(create().get("id").asText());
    try (var first = entityManagerFactory.createEntityManager();
        var second = entityManagerFactory.createEntityManager()) {
      first.getTransaction().begin();
      second.getTransaction().begin();
      var a = first.find(RentalRequest.class, id);
      var b = second.find(RentalRequest.class, id);
      a.transitionTo(RentalStatus.IN_REVIEW, Instant.now());
      first.getTransaction().commit();
      b.transitionTo(RentalStatus.REJECTED, Instant.now());
      try {
        assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
      } finally {
        second.getTransaction().rollback();
      }
    }
    assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(RentalStatus.IN_REVIEW);
  }
}
