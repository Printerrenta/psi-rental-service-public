package lt.printerrental.rental;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.context.annotation.Import(lt.printerrental.auth.SecurityConfiguration.class)
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
@WebMvcTest(RentalRequestController.class)
class RentalRequestControllerTest {
  private static final String PATH = "/api/rental-requests";
  private static final UUID ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String VALID =
      """
      {"estimatedMonthlyPages":1000,"colorPrintingRequired":false,"customerName":"Jonas",
       "customerEmail":"jonas@example.com","startDate":"2027-01-01","endDate":"2027-01-02"}
      """;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  org.springframework.security.oauth2.jwt.JwtDecoder decoder;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  lt.printerrental.auth.SigningKeyProvider signingKeys;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  lt.printerrental.auth.UserJwtAuthenticationConverter converter;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  lt.printerrental.messaging.OutboxDispatchJob outbox;

  @Autowired MockMvc mvc;
  @MockitoBean RentalRequestService service;

  @Test
  void createsWithLocation() throws Exception {
    when(service.create(any()))
        .thenReturn(
            new RentalResponse(
                ID,
                null,
                null,
                "Jonas",
                "jonas@example.com",
                1000,
                false,
                LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 2),
                null,
                RentalStatus.PENDING,
                Instant.EPOCH,
                Instant.EPOCH,
                0L));
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", PATH + "/" + ID))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.version").value(0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "null", "1.5"})
  void rejectsInvalidMonthlyPages(String pages) throws Exception {
    mvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("1000", pages)))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void requiresPrintingNeedsAndRejectsClientPrinterAssignment() throws Exception {
    for (String body :
        new String[] {
          VALID.replace("\"estimatedMonthlyPages\":1000,", ""),
          VALID.replace("\"colorPrintingRequired\":false,", ""),
          VALID.replace("false", "null"),
          VALID.replace("1000", "2147483648"),
          VALID.replace("{", "{\"resourceId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\",")
        }) {
      mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{",
        "[]",
        "{\"resourceId\":\"bad-id\"}",
        "{\"customerName\":\" \",\"customerEmail\":\"bad\"}"
      })
  void rejectsInvalidBody(String body) throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsBadEmailAndBlankNameWithFieldErrors() throws Exception {
    mvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("jonas@example.com", "bad-email").replace("Jonas", " ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors").isArray());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsOversizedNotes() throws Exception {
    mvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.strip().replace("}", ",\"notes\":\"" + "a".repeat(2001) + "\"}")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsUnknownField() throws Exception {
    mvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.strip().replace("}", ",\"status\":\"APPROVED\"}")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?page=abc", "?status=UNKNOWN"})
  void rejectsInvalidListParameters(String query) throws Exception {
    mvc.perform(get(PATH + query)).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsMalformedId() throws Exception {
    mvc.perform(get(PATH + "/not-a-uuid")).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"status\":\"UNKNOWN\",\"version\":0}",
        "{\"status\":1,\"version\":0}",
        "{\"status\":\"APPROVED\",\"version\":0.5}",
        "{\"status\":\"APPROVED\"}",
        "{\"status\":\"APPROVED\",\"version\":-1}"
      })
  void rejectsInvalidStatusBody(String body) throws Exception {
    mvc.perform(
            patch(PATH + "/" + ID + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void returnsNotFoundProblem() throws Exception {
    when(service.get(ID)).thenThrow(new RentalNotFoundException(ID));
    mvc.perform(get(PATH + "/" + ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void returnsConflictForForbiddenTransition() throws Exception {
    when(service.changeStatus(any(), any()))
        .thenThrow(new RentalConflictException("Invalid transition"));
    mvc.perform(
            patch(PATH + "/" + ID + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\",\"version\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  void returnsConflictForConcurrentWrite() throws Exception {
    when(service.changeStatus(any(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(RentalRequest.class, ID));
    mvc.perform(
            patch(PATH + "/" + ID + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"version\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  void returnsBadRequestForInvalidDates() throws Exception {
    when(service.create(any()))
        .thenThrow(new IllegalArgumentException("endDate must be on or after startDate"));
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("endDate must be on or after startDate"));
  }
}
