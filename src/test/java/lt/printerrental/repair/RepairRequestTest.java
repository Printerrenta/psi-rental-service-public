package lt.printerrental.repair;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class RepairRequestTest {
  private RepairRequest request() {
    return new RepairRequest(
        new RepairContractAccess.ActiveContract(UUID.randomUUID(), UUID.randomUUID(), "alice"),
        "  Paper feed rollers are broken  ",
        Instant.EPOCH);
  }

  static Stream<Arguments> transitions() {
    return Arrays.stream(RepairStatus.values())
        .flatMap(from -> Arrays.stream(RepairStatus.values()).map(to -> Arguments.of(from, to)));
  }

  @ParameterizedTest
  @MethodSource("transitions")
  void enforcesLifecycle(RepairStatus from, RepairStatus to) {
    var r = request();
    if (from == RepairStatus.IN_PROGRESS || from == RepairStatus.COMPLETED)
      r.transitionTo(RepairStatus.IN_PROGRESS, null, Instant.EPOCH.plusSeconds(1));
    if (from == RepairStatus.COMPLETED)
      r.transitionTo(from, "Replaced rollers", Instant.EPOCH.plusSeconds(2));
    if (from == RepairStatus.CANCELLED) r.transitionTo(from, null, Instant.EPOCH.plusSeconds(1));
    var allowed =
        (from == RepairStatus.OPEN
                && (to == RepairStatus.IN_PROGRESS || to == RepairStatus.CANCELLED))
            || (from == RepairStatus.IN_PROGRESS
                && (to == RepairStatus.COMPLETED || to == RepairStatus.CANCELLED));
    if (allowed) {
      r.transitionTo(
          to,
          to == RepairStatus.COMPLETED ? "  Replaced rollers  " : null,
          Instant.EPOCH.plusSeconds(3));
      assertThat(r.getStatus()).isEqualTo(to);
      assertThat(r.getResolution())
          .isEqualTo(to == RepairStatus.COMPLETED ? "Replaced rollers" : null);
    } else
      assertThatThrownBy(
              () ->
                  r.transitionTo(
                      to,
                      to == RepairStatus.COMPLETED ? "Replaced rollers" : null,
                      Instant.EPOCH.plusSeconds(3)))
          .isInstanceOf(RepairConflictException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "short", "          "})
  void rejectsInvalidDescriptionEvenWithoutHttp(String description) {
    assertThatThrownBy(
            () ->
                new RepairRequest(
                    new RepairContractAccess.ActiveContract(
                        UUID.randomUUID(), UUID.randomUUID(), "alice"),
                    description,
                    Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void trimsDescriptionAndBoundsLargeInput() {
    assertThat(request().getDescription()).isEqualTo("Paper feed rollers are broken");
    assertThatThrownBy(
            () ->
                new RepairRequest(
                    new RepairContractAccess.ActiveContract(
                        UUID.randomUUID(), UUID.randomUUID(), "alice"),
                    "x".repeat(4001),
                    Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "four"})
  void completionNeedsResolutionWithoutChangingStateOnFailure(String resolution) {
    var r = request();
    r.transitionTo(RepairStatus.IN_PROGRESS, null, Instant.EPOCH);
    assertThatThrownBy(() -> r.transitionTo(RepairStatus.COMPLETED, resolution, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(r.getStatus()).isEqualTo(RepairStatus.IN_PROGRESS);
  }

  @Test
  void cancellationCannotInventResolution() {
    var r = request();
    assertThatThrownBy(
            () -> r.transitionTo(RepairStatus.CANCELLED, "An internal note", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(r.getStatus()).isEqualTo(RepairStatus.OPEN);
  }
}
