package lt.printerrental.repair;

public enum RepairStatus {
  OPEN,
  IN_PROGRESS,
  COMPLETED,
  CANCELLED;

  boolean canTransitionTo(RepairStatus next) {
    return switch (this) {
      case OPEN -> next == IN_PROGRESS || next == CANCELLED;
      case IN_PROGRESS -> next == COMPLETED || next == CANCELLED;
      case COMPLETED, CANCELLED -> false;
    };
  }
}
