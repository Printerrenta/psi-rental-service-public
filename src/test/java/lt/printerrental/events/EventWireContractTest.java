package lt.printerrental.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EventWireContractTest {
  @Test
  void preservesThePublishedVersionOneJsonContract() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    byte[] json;
    try (var input = getClass().getResourceAsStream("/contracts/repair-request-created-v1.json")) {
      json = input.readAllBytes();
    }
    var event = mapper.readValue(json, DomainEvent.class);
    assertThat(event.schemaVersion()).isEqualTo(1);
    assertThat(event.type()).isEqualTo("RepairRequestCreated");
    assertThat(event.ownerSubject()).isEqualTo("alice");
    assertThat(mapper.readTree(mapper.writeValueAsBytes(event))).isEqualTo(mapper.readTree(json));
    assertThat(event.data()).doesNotContainKeys("description", "resolution");
  }
}
