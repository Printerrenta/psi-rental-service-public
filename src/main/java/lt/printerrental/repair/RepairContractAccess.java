package lt.printerrental.repair;

import java.util.UUID;

/** The rental adapter verifies ownership and keeps the contract active during creation. */
public interface RepairContractAccess {
  ActiveContract requireActive(UUID contractId);

  record ActiveContract(UUID contractId, UUID printerId, String ownerSubject) {}
}
