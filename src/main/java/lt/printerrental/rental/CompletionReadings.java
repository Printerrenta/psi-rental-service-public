package lt.printerrental.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lt.printerrental.accounting.AccountingRepository;
import lt.printerrental.transport.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Persist final usage through metering's validation, audit and durable billing outbox. */
@Component
public class CompletionReadings {
  private final AccountingRepository accounting;
  private final ServiceHttpClient client;
  private final ObjectMapper json;
  private final String url;

  public CompletionReadings(AccountingRepository accounting, ServiceHttpClient client,
      ObjectMapper json, @Value("${METERING_SERVICE_URL:}") String url) {
    this.accounting = accounting; this.client = client; this.json = json; this.url = url;
  }

  public void record(RentalContract contract, CompleteContract input) {
    var settings = accounting.find(contract.getId());
    // Legacy contracts without four-counter accounting do not participate in billing.
    if (settings.isEmpty()) return;
    var values = input.finalCounters();
    if (values == null) {
      if (settings.get().a3Capable())
        throw new IllegalArgumentException("A3 sutarties užbaigimui pateikite visus keturis skaitiklius.");
      values = List.of(input.finalMonoReading(), input.finalColorReading(), 0L, 0L);
    }
    if (values.size() != 4 || values.stream().anyMatch(n -> n == null || n < 0)
        || !values.get(0).equals(input.finalMonoReading())
        || !values.get(1).equals(input.finalColorReading()))
      throw new IllegalArgumentException("Galutiniai skaitikliai nesutampa su sutarties rodmenimis.");
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (url.isBlank() || !(authentication instanceof JwtAuthenticationToken))
      throw new UpstreamUnavailableException();
    var bearer = "Bearer " + ((JwtAuthenticationToken) authentication).getToken().getTokenValue();
    var path = "/api/metering/contracts/" + contract.getId();
    JsonNode accepted = null;
    boolean pending = false, complete = false;
    long version = -1;
    var finalDate = input.finalReadingDate().toString();
    for (int page = 0; page < 100; page++) {
      var view = send(path + "?page=" + page + "&size=100", "GET", null, bearer);
      if (!contract.getId().toString().equals(view.path("contract").path("contractId").asText())
          || !view.path("version").canConvertToLong() || !view.path("readings").isArray())
        throw new UpstreamUnavailableException();
      long current = view.path("version").asLong();
      if (version >= 0 && version != current)
        throw new RentalConflictException("Rodmenys pasikeitė. Atnaujinkite duomenis.");
      version = current;
      for (var reading : view.path("readings")) {
        var status = reading.path("status").asText();
        var date = reading.path("asOf").asText();
        if (status.equals("ACCEPTED") && date.compareTo(finalDate) > 0)
          throw new RentalConflictException("Yra vėlesnių priimtų rodmenų. Patikrinkite užbaigimo datą.");
        if (!date.equals(finalDate)) continue;
        if (status.equals("ACCEPTED")) accepted = reading;
        if (status.equals("PENDING")) pending = true;
      }
      if (view.path("readings").size() < 100) { complete = true; break; }
    }
    if (!complete) throw new RentalConflictException("Rodmenų istorija per didelė užbaigimo patikrai.");
    if (accepted != null) {
      if (!matches(accepted.path("counters"), values))
        throw new RentalConflictException("Šiai datai jau priimti kitokie rodmenys. Pirmiausia juos patikslinkite.");
      if (!pending) return;
    }
    if (pending) throw reviewRequired();
    // Deterministic intent survives a timeout or a rental transaction rollback.
    var key = UUID.nameUUIDFromBytes((contract.getId() + ":completion:" + finalDate + ":" + values)
        .getBytes(StandardCharsets.UTF_8));
    var result = send(path + "/readings", "POST", Map.of("requestKey", key, "version", version,
        "asOf", finalDate, "counters", values), bearer);
    if (!result.path("reading").path("status").asText().equals("ACCEPTED")) throw reviewRequired();
  }

  private boolean matches(JsonNode counters, List<Long> expected) {
    if (!counters.isArray() || counters.size() != 4) return false;
    for (int i = 0; i < 4; i++)
      if (!counters.get(i).isIntegralNumber() || !counters.get(i).canConvertToLong()
          || counters.get(i).asLong() != expected.get(i)) return false;
    return true;
  }

  private RentalConflictException reviewRequired() {
    return new RentalConflictException("Galutiniai rodmenys laukia peržiūros. Patvirtinkite juos skiltyje „Rodmenys“ ir pakartokite užbaigimą.");
  }

  private JsonNode send(String path, String method, Object body, String bearer) {
    try {
      var response = client.send(url, path, method, body == null ? null : json.writeValueAsBytes(body),
          body == null ? null : "application/json", bearer);
      if (response.statusCode() == 409)
        throw new RentalConflictException("Rodmenys pasikeitė. Atnaujinkite duomenis ir bandykite dar kartą.");
      if (response.statusCode() == 400 || response.statusCode() == 422)
        throw new IllegalArgumentException("Rodmenų apskaita atmetė galutinius skaitiklius. Patikrinkite datą ir kaupiamuosius rodmenis.");
      if (response.statusCode() != 200 && response.statusCode() != 201)
        throw new UpstreamUnavailableException();
      return json.readTree(response.body());
    } catch (java.io.IOException error) { throw new UpstreamUnavailableException(); }
  }
}
