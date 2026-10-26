package lt.printerrental.rental;

public enum RentalStatus {
  PENDING,
  IN_REVIEW,
  APPROVED,
  REJECTED,
  CANCELLED,
  COMPLETED;

  public boolean canTransitionTo(RentalStatus next) {
    return switch (this) {
      case PENDING -> next == IN_REVIEW || next == REJECTED || next == CANCELLED;
      case IN_REVIEW -> next == APPROVED || next == REJECTED || next == CANCELLED;
      case APPROVED -> next == CANCELLED || next == COMPLETED;
      case REJECTED, CANCELLED, COMPLETED -> false;
    };
  }
}
