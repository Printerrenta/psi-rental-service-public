package lt.printerrental.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class RentalAccessPolicy {
  private final CurrentUser currentUser;

  public RentalAccessPolicy(CurrentUser currentUser) {
    this.currentUser = currentUser;
  }

  public void requireOwner(String owner) {
    if (!currentUser.isStaff() && !currentUser.subject().equals(owner)) {
      throw new AccessDeniedException("Access denied");
    }
  }

  public void requireStaff() {
    if (!currentUser.isStaff()) throw new AccessDeniedException("Staff access required");
  }
}
