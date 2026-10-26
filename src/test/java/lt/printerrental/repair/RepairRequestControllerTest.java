package lt.printerrental.repair;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RepairRequestController.class)
@Import(lt.printerrental.auth.SecurityConfiguration.class)
class RepairRequestControllerTest {
  @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
  @MockitoBean lt.printerrental.auth.SigningKeyProvider keys;
  @MockitoBean lt.printerrental.auth.UserJwtAuthenticationConverter converter;
  @MockitoBean lt.printerrental.messaging.OutboxDispatchJob outbox;
  @MockitoBean RepairRequestService service;
  @Autowired MockMvc mvc;
  private static final String BASE = "/api/repair-requests";
  private static final UUID ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  @Test
  void customerCanSubmitValidatedRequest() throws Exception {
    when(service.create(any()))
        .thenReturn(
            new RepairResponse(
                ID,
                ID,
                ID,
                "Printer rollers are broken",
                RepairStatus.OPEN,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                0L));
    mvc.perform(
            post(BASE)
                .with(user("alice").roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"contractId\":\""
                        + ID
                        + "\",\"description\":\"Printer rollers are broken\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", BASE + "/" + ID))
        .andExpect(jsonPath("$.status").value("OPEN"));
  }

  @Test
  void anonymousIsDenied() throws Exception {
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "\"\"", "\"short\"", "\"          \""})
  void invalidDescriptionNeverReachesService(String description) throws Exception {
    mvc.perform(
            post(BASE)
                .with(user("alice").roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractId\":\"" + ID + "\",\"description\":" + description + "}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(strings = {"?size=0", "?size=101", "?page=-1", "?status=UNKNOWN", "?contractId=bad"})
  void rejectsBadFilters(String query) throws Exception {
    mvc.perform(get(BASE + query).with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"status\":\"IN_PROGRESS\"}",
        "{\"status\":\"IN_PROGRESS\",\"version\":-1}",
        "{\"status\":1,\"version\":0}"
      })
  void rejectsMalformedStatusChange(String body) throws Exception {
    mvc.perform(
            patch(BASE + "/" + ID + "/status")
                .with(user("staff").roles("EMPLOYEE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void missingAndConflictUseProblemDetail() throws Exception {
    when(service.get(ID)).thenThrow(new RepairNotFoundException(ID));
    mvc.perform(get(BASE + "/" + ID).with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
    when(service.changeStatus(eq(ID), any())).thenThrow(new RepairConflictException("Changed"));
    mvc.perform(
            patch(BASE + "/" + ID + "/status")
                .with(user("staff").roles("EMPLOYEE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":0}"))
        .andExpect(status().isConflict());
  }
}
