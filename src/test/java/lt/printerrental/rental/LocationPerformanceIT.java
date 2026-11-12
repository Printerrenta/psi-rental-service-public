package lt.printerrental.rental;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lt.printerrental.auth.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;

/** Fixed-size, warm-connection measurements; query counts are the deterministic CI budget. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocationPerformanceIT {
  @Container static final lt.printerrental.testing.TestPostgresContainer POSTGRES =
      new lt.printerrental.testing.TestPostgresContainer("postgres:17-alpine");
  private final AtomicInteger statements = new AtomicInteger();
  private final CurrentUser user = mock(CurrentUser.class);
  private final RentalRequestService requests = mock(RentalRequestService.class);
  private final List<RentalResponse> rentals = new ArrayList<>();
  private SingleConnectionDataSource connection;
  private JdbcTemplate jdbc;
  private TransactionTemplate transaction;
  private LocationService service;

  @BeforeAll void prepare() {
    var raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(raw).schemas("public").load().migrate();
    connection = new SingleConnectionDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    var counted = new DelegatingDataSource(connection) {
      @Override public Connection getConnection() throws java.sql.SQLException {
        return count(super.getConnection());
      }
    };
    jdbc = new JdbcTemplate(counted);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(counted));
    transaction.setReadOnly(true);
    service = new LocationService(jdbc, user, new RentalAccessPolicy(user), requests, "public");
    var day = LocalDate.of(2026, 11, 12);
    for (int i = 0; i < 100; i++) {
      var location = new UUID(0, i + 1);
      var printer = new UUID(1, i + 1);
      var rental = new UUID(2, i + 1);
      var owner = i % 2 == 0 ? "alice" : "bob";
      jdbc.update("INSERT INTO customer_locations(id,owner_subject,name,address,city,created_at) VALUES(?,?,?,?,?,?)",
          location, owner, "Location " + i, "Address " + i, "Vilnius", java.sql.Timestamp.from(Instant.EPOCH.plusSeconds(i)));
      jdbc.update("INSERT INTO printers(id,serial_number,manufacturer,model,color_capable) VALUES(?,?,?,?,false)",
          printer, "PERF-" + i, "Maker", "Model");
      jdbc.update("INSERT INTO printer_locations(printer_id,location_id,version) VALUES(?,?,?)", printer, location, i);
      jdbc.update("INSERT INTO rental_requests(id,customer_name,customer_email,start_date,end_date,status,created_at,updated_at,owner_subject,version,estimated_monthly_pages,color_printing_required) VALUES(?,?,?,?,?,'PENDING',now(),now(),?,0,100,false)",
          rental, "Client", "client@example.test", day, day.plusDays(1), owner);
      if (i % 10 != 0) jdbc.update("INSERT INTO rental_request_locations(request_id,location_id) VALUES(?,?)", rental, location);
      rentals.add(new RentalResponse(rental, null, null, "Client", "client@example.test", 100, false,
          day, day.plusDays(1), null, RentalStatus.PENDING, Instant.EPOCH, Instant.EPOCH, 0L));
    }
  }

  private Connection count(Connection delegate) {
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, args) -> {
          if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement")) statements.incrementAndGet();
          try { return method.invoke(delegate, args); }
          catch (InvocationTargetException error) { throw error.getCause(); }
        });
  }

  private int measure(String name, int size, Supplier<?> operation) {
    for (int i = 0; i < 3; i++) transaction.execute(status -> operation.get());
    var times = new ArrayList<Double>();
    int maximum = 0;
    for (int i = 0; i < 9; i++) {
      statements.set(0);
      long start = System.nanoTime();
      transaction.execute(status -> operation.get());
      times.add((System.nanoTime() - start) / 1_000_000.0);
      maximum = Math.max(maximum, statements.get());
    }
    Collections.sort(times);
    System.out.printf(Locale.ROOT, "PERF %s size=%d queries=%d median_ms=%.3f min_ms=%.3f max_ms=%.3f%n",
        name, size, maximum, times.get(4), times.getFirst(), times.getLast());
    return maximum;
  }

  @BeforeEach void staff() { when(user.isStaff()).thenReturn(true); when(user.subject()).thenReturn("alice"); }
  @AfterAll void close() { if (connection != null) connection.destroy(); }

  @Test void locationPagesHaveAConstantQueryBudget() {
    int maximum = 0;
    for (int size : List.of(10, 50, 100)) {
      var result = service.list(0, size);
      assertThat(result.content()).hasSize(size);
      assertThat(result.totalElements()).isEqualTo(100);
      assertThat(result.content()).allSatisfy(row -> {
        assertThat(row.printers()).hasSize(1);
        assertThat(row.printers().getFirst().printerId()).isEqualTo(new UUID(1, row.id().getLeastSignificantBits()));
        assertThat(row.printers().getFirst().version()).isEqualTo(row.id().getLeastSignificantBits() - 1);
      });
      maximum = Math.max(maximum, measure("locations", size, () -> service.list(0, size)));
    }
    assertThat(maximum).as("location page SQL budget").isLessThanOrEqualTo(3);
  }

  @Test void requestLocationLookupsHaveAConstantQueryBudget() {
    int maximum = 0;
    for (int size : List.of(10, 50, 100)) {
      when(requests.list(null, 0, size)).thenReturn(new PageImpl<>(rentals.subList(0, size), PageRequest.of(0, size), 100));
      var result = service.listRequests(0, size);
      assertThat(result.content()).hasSize(size);
      assertThat(result.content().stream().filter(row -> row.locationId() == null)).hasSize(size / 10);
      assertThat(result.content()).allSatisfy(row -> {
        long index = row.request().id().getLeastSignificantBits();
        assertThat(row.locationId()).isEqualTo((index - 1) % 10 == 0 ? null : new UUID(0, index));
      });
      maximum = Math.max(maximum, measure("request-location-lookups", size, () -> service.listRequests(0, size)));
    }
    assertThat(maximum).as("request location lookup SQL budget; rental page query is measured separately").isLessThanOrEqualTo(1);
  }

  @Test void paginationAndOwnershipRemainIntact() {
    when(user.isStaff()).thenReturn(false);
    var result = service.list(1, 20);
    assertThat(result.totalElements()).isEqualTo(50);
    assertThat(result.content()).hasSize(20).allSatisfy(row -> assertThat(row.ownerSubject()).isEqualTo("alice"));
    assertThat(service.list(9, 20).content()).isEmpty();
    assertThatThrownBy(() -> service.get(new UUID(0, 2))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(service.get(new UUID(0, 1)).printers()).hasSize(1);
  }

  @Test void emptyPagesAndMultiplePrintersPreserveTheResponse() {
    when(requests.list(null, 0, 100)).thenReturn(Page.empty());
    statements.set(0);
    assertThat(service.listRequests(0, 100).content()).isEmpty();
    assertThat(statements.get()).isZero();
    var write = new TransactionTemplate(transaction.getTransactionManager());
    write.execute(status -> {
      var empty = new UUID(0, 101);
      jdbc.update("INSERT INTO customer_locations(id,owner_subject,name,address,city) VALUES(?,?,?,?,?)",
          empty, "alice", "Empty", "Address", "Vilnius");
      assertThat(service.get(empty).printers()).isEmpty();
      var lower = new UUID(0, 999);
      var upper = new UUID(9, 999);
      for (var printer : List.of(lower, upper)) {
        jdbc.update("INSERT INTO printers(id,serial_number,manufacturer,model,color_capable) VALUES(?,?,?,?,false)",
            printer, printer.toString(), "Maker", "Model");
        jdbc.update("INSERT INTO printer_locations(printer_id,location_id,version) VALUES(?,?,7)", printer, new UUID(0, 1));
      }
      assertThat(service.get(new UUID(0, 1)).printers()).extracting(LocationService.Placement::printerId)
          .containsExactly(lower, new UUID(1, 1), upper);
      assertThat(service.list(0, 1).content().getFirst().printers()).extracting(LocationService.Placement::printerId)
          .containsExactly(lower, new UUID(1, 1), upper);
      status.setRollbackOnly();
      return null;
    });
  }
}
