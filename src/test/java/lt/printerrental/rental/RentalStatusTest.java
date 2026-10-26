package lt.printerrental.rental;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RentalStatusTest {
  static Stream<Arguments> transitions() {
    var allowed =
        Set.of(
            "PENDING:IN_REVIEW",
            "PENDING:REJECTED",
            "PENDING:CANCELLED",
            "IN_REVIEW:APPROVED",
            "IN_REVIEW:REJECTED",
            "IN_REVIEW:CANCELLED",
            "APPROVED:CANCELLED",
            "APPROVED:COMPLETED");
    return Stream.of(RentalStatus.values())
        .flatMap(
            from ->
                Stream.of(RentalStatus.values())
                    .map(to -> Arguments.of(from, to, allowed.contains(from + ":" + to))));
  }

  @ParameterizedTest
  @MethodSource("transitions")
  void checksEveryStatusPair(RentalStatus from, RentalStatus to, boolean allowed) {
    assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
  }
}
