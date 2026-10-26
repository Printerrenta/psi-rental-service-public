package lt.printerrental.rental;

import java.util.UUID;

/** Local transaction port; implementations cannot call another service during contract mutation. */
public interface ContractAccountingObserver {
  void changed(UUID contractId, String ownerSubject);
}
