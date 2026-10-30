package lt.printerrental.rental;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"OUTBOX_INITIAL_DELAY_MS=3600000", "NEON_AUTH_BASE_URL=https://auth.example.test/auth", "NEON_AUTH_JWKS_URL=https://auth.example.test/auth/jwks"})
@AutoConfigureMockMvc
@Testcontainers
class LocationIT {
  @Container static final PostgreSQLContainer<?> POSTGRES = new lt.printerrental.testing.TestPostgresContainer("postgres:17-alpine");
  @DynamicPropertySource static void db(DynamicPropertyRegistry p) {
    p.add("DB_SCHEMA",()->"public");
    p.add("spring.datasource.url",POSTGRES::getJdbcUrl); p.add("spring.datasource.username",POSTGRES::getUsername); p.add("spring.datasource.password",POSTGRES::getPassword);
    p.add("spring.flyway.url",POSTGRES::getJdbcUrl); p.add("spring.flyway.user",POSTGRES::getUsername); p.add("spring.flyway.password",POSTGRES::getPassword);
  }
  @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
  @MockitoBean lt.printerrental.auth.SigningKeyProvider keys;
  @MockitoBean lt.printerrental.auth.UserIdentityResolver identities;
  @MockitoBean lt.printerrental.events.EventPublisher publisher;
  @MockitoBean lt.printerrental.auth.CustomerDirectory customers;
  @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
  @Autowired PrinterRepository printers;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  private static final String PATH="/api/rental-requests/locations";
  @Test void staffCreatedContractBelongsToVerifiedCustomerAndIsInvisibleToOtherCustomers() throws Exception {
    org.mockito.Mockito.when(customers.get("alice")).thenReturn(new lt.printerrental.auth.AppUser("alice","alice@example.com","Alice",lt.printerrental.auth.UserRole.CUSTOMER));
    var tomorrow=LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
    String body=json.writeValueAsString(new CreateRentalRequest("Alice","wrong@example.com",500,false,tomorrow,tomorrow,null,"alice"));
    mvc.perform(post("/api/rental-requests").with(user("bob").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    var result=mvc.perform(post("/api/rental-requests").with(user("employee").roles("EMPLOYEE")).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.customerEmail").value("alice@example.com")).andReturn();
    String id=json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    assertThat(jdbc.queryForObject("SELECT owner_subject FROM rental_requests WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("alice");
    mvc.perform(patch("/api/rental-requests/"+id+"/status").with(user("employee").roles("EMPLOYEE")).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_REVIEW\",\"version\":0}")).andExpect(status().isOk());
    var printer=printers.saveAndFlush(new Printer(new CreatePrinter("OWNER-TEST","Brother","Test",false)));
    mvc.perform(patch("/api/rental-requests/"+id+"/printer").with(user("employee").roles("EMPLOYEE")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version",1,"printerId",printer.getId())))).andExpect(status().isOk());
    mvc.perform(patch("/api/rental-requests/"+id+"/status").with(user("employee").roles("EMPLOYEE")).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\",\"version\":2}")).andExpect(status().isOk());
    var contractResult=mvc.perform(post("/api/contracts").with(user("employee").roles("EMPLOYEE")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new CreateContract(UUID.fromString(id),3L,new java.math.BigDecimal("35"),0,0,new java.math.BigDecimal("0.01"),java.math.BigDecimal.ZERO))))
        .andExpect(status().isCreated()).andReturn();
    String contract=json.readTree(contractResult.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(get("/api/contracts/"+contract).with(user("alice").roles("CUSTOMER"))).andExpect(status().isOk());
    mvc.perform(get("/api/contracts/"+contract).with(user("bob").roles("CUSTOMER"))).andExpect(status().isForbidden());
    mvc.perform(get("/api/contracts").with(user("alice").roles("CUSTOMER"))).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/api/contracts").with(user("bob").roles("CUSTOMER"))).andExpect(jsonPath("$.totalElements").value(0));
  }
  @BeforeEach void clean() { jdbc.execute("TRUNCATE customer_locations,printers,rental_requests CASCADE"); }
  private JsonNode location(String owner) throws Exception {
    var result=mvc.perform(post(PATH).with(user("staff").roles("EMPLOYEE")).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("name","Biuras","address","Test gatvė 1","city","Vilnius","ownerSubject",owner))))
        .andExpect(status().isCreated()).andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }
  private String request(String location) throws Exception {
    var day=LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();
    return json.writeValueAsString(Map.of("locationId",location,"request",Map.of("customerName","Alice","customerEmail","alice@example.com","estimatedMonthlyPages",1000,"colorPrintingRequired",false,"startDate",day,"endDate",day,"notes","Test")));
  }
  @Test void locationsAreOwnedAndCannotBeForgedOrEditedByOtherCustomer() throws Exception {
    var l=location("alice");
    mvc.perform(get(PATH).with(user("alice").roles("CUSTOMER"))).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get(PATH).with(user("bob").roles("CUSTOMER"))).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(post(PATH).with(user("bob").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"address\":\"A\",\"city\":\"C\",\"ownerSubject\":\"alice\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(patch(PATH+"/"+l.get("id").asText()).with(user("bob").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"address\":\"A\",\"city\":\"C\",\"version\":0}"))
        .andExpect(status().isForbidden());
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
  }
  @Test void updateRequiresCurrentVersion() throws Exception {
    var l=location("alice"); String body="{\"name\":\"Kitas\",\"address\":\"Adresas\",\"city\":\"Kaunas\",\"version\":0}";
    mvc.perform(patch(PATH+"/"+l.get("id").asText()).with(user("alice").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
    mvc.perform(patch(PATH+"/"+l.get("id").asText()).with(user("alice").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
  }
  @Test void requestAndLocationLinkArePersistedAndFilteredTogether() throws Exception {
    var l=location("alice");
    mvc.perform(post(PATH+"/requests").with(user("alice").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(request(l.get("id").asText())))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.request.status").value("PENDING")).andExpect(jsonPath("$.locationId").value(l.get("id").asText()));
    mvc.perform(get(PATH+"/requests").with(user("bob").roles("CUSTOMER"))).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get(PATH+"/requests").with(user("alice").roles("CUSTOMER"))).andExpect(jsonPath("$.content[0].locationId").value(l.get("id").asText()));
    mvc.perform(get(PATH+"/requests").with(user("staff").roles("EMPLOYEE"))).andExpect(jsonPath("$.totalElements").value(1));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM rental_request_locations",Long.class)).isEqualTo(1);
  }
  @Test void foreignLocationAndInvalidRequestCreateNothing() throws Exception {
    var l=location("alice");
    mvc.perform(post(PATH+"/requests").with(user("bob").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(request(l.get("id").asText())))
        .andExpect(status().isForbidden());
    mvc.perform(post(PATH+"/requests").with(user("alice").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(request(l.get("id").asText()).replace("1000","0")))
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM rental_requests",Long.class)).isZero();
  }
  @Test void onlyStaffAssignPrintersAndStalePlacementIsRejected() throws Exception {
    var l=location("alice"); var p=printers.saveAndFlush(new Printer(new CreatePrinter("LOC-TEST","Brother","Test",false)));
    String body=json.writeValueAsString(Map.of("locationId",l.get("id").asText(),"version",0));
    mvc.perform(patch(PATH+"/printers/"+p.getId()).with(user("alice").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    mvc.perform(patch(PATH+"/printers/"+p.getId()).with(user("staff").roles("EMPLOYEE")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.printers[0].version").value(1));
    mvc.perform(patch(PATH+"/printers/"+p.getId()).with(user("staff").roles("EMPLOYEE")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s -> printers.clearOtherCustomerLocation(p.getId(),"bob"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM printer_locations",Long.class)).isZero();
  }
  @Test void customerCanEditOnlyOwnedPendingRequestWithCurrentVersion() throws Exception {
    var l=location("alice");
    var created=mvc.perform(post(PATH+"/requests").with(user("alice").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(request(l.get("id").asText())))
        .andExpect(status().isCreated()).andReturn();
    String id=json.readTree(created.getResponse().getContentAsString()).get("request").get("id").asText();
    var update=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(request(l.get("id").asText())); update.put("version",0); ((com.fasterxml.jackson.databind.node.ObjectNode)update.get("request")).put("estimatedMonthlyPages",2000);
    mvc.perform(patch(PATH+"/requests/"+id).with(user("bob").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isForbidden());
    mvc.perform(patch(PATH+"/requests/"+id).with(user("alice").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isNoContent());
    mvc.perform(get(PATH+"/requests").with(user("alice").roles("CUSTOMER"))).andExpect(jsonPath("$.content[0].request.estimatedMonthlyPages").value(2000)).andExpect(jsonPath("$.content[0].request.version").value(1));
    mvc.perform(patch(PATH+"/requests/"+id).with(user("alice").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isConflict());
    mvc.perform(patch("/api/rental-requests/"+id+"/status").with(user("staff").roles("EMPLOYEE")).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_REVIEW\",\"version\":1}")).andExpect(status().isOk());
    update.put("version",2);
    mvc.perform(patch(PATH+"/requests/"+id).with(user("alice").roles("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isConflict());
  }
}
