package lt.printerrental.rental;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.context.annotation.Import(lt.printerrental.auth.SecurityConfiguration.class)
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
@WebMvcTest(ContractController.class)
class ContractCompletionControllerTest {
  private static final String PATH = "/api/contracts/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/complete";
  private static final String VALID =
      """
      {"version":1,"finalMonoReading":100,"finalColorReading":20,"finalReadingDate":"2026-10-21"}
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
  @Autowired ObjectMapper mapper;
  @MockitoBean ContractService service;

  @ParameterizedTest
  @ValueSource(strings = {"version", "finalMonoReading", "finalColorReading", "finalReadingDate"})
  void requiresEveryField(String field) throws Exception {
    var json = (ObjectNode) mapper.readTree(VALID);
    json.remove(field);
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "finalMonoReading", "finalColorReading"})
  void rejectsNullNegativeFractionalAndOverflowingNumbers(String field) throws Exception {
    for (String value : new String[] {"null", "-1", "0.5", "9223372036854775808"}) {
      var json = (ObjectNode) mapper.readTree(VALID);
      json.set(field, mapper.readTree(value));
      mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
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
        "{\"version\":1,\"finalMonoReading\":100,\"finalColorReading\":20,\"finalReadingDate\":null}",
        "{\"version\":1,\"finalMonoReading\":100,\"finalColorReading\":20,\"finalReadingDate\":\"bad-date\"}",
        "{\"version\":1,\"finalMonoReading\":100,\"finalColorReading\":20,\"finalReadingDate\":\"2026-10-21\",\"status\":\"COMPLETED\"}"
      })
  void rejectsInvalidBodyAndClientStatusOverride(String body) throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
