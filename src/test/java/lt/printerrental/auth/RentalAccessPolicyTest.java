package lt.printerrental.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class RentalAccessPolicyTest {
  @Test
  void customerCanReadOwnedRecordsButNotLegacyOrForeignRecords() {
    var user = mock(CurrentUser.class);
    when(user.subject()).thenReturn("alice");
    var policy = new RentalAccessPolicy(user);
    policy.requireOwner("alice");
    assertThatThrownBy(() -> policy.requireOwner("bob")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> policy.requireOwner(null)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(policy::requireStaff).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void staffCanReadUnownedRecordsAndManageFiles() {
    var user = mock(CurrentUser.class);
    when(user.isStaff()).thenReturn(true);
    var policy = new RentalAccessPolicy(user);
    policy.requireOwner(null);
    policy.requireStaff();
  }
}
