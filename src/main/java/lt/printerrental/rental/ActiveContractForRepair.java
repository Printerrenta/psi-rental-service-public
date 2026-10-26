package lt.printerrental.rental;

import java.util.UUID;
import lt.printerrental.auth.RentalAccessPolicy;
import lt.printerrental.repair.RepairConflictException;
import lt.printerrental.repair.RepairContractAccess;
import org.springframework.stereotype.Component;

@Component
public class ActiveContractForRepair implements RepairContractAccess {
  private final ContractRepository contracts;
  private final RentalRequestRepository requests;
  private final RentalAccessPolicy access;

  public ActiveContractForRepair(
      ContractRepository contracts, RentalRequestRepository requests, RentalAccessPolicy access) {
    this.contracts = contracts;
    this.requests = requests;
    this.access = access;
  }

  public ActiveContract requireActive(UUID id) {
    var contract = contracts.findForRepair(id).orElseThrow(() -> new ContractNotFoundException(id));
    var request =
        requests
            .findById(contract.getRentalRequestId())
            .orElseThrow(() -> new ContractNotFoundException(id));
    access.requireOwner(request.getOwnerSubject());
    if (contract.getStatus() != ContractStatus.ACTIVE)
      throw new RepairConflictException("Repair requests require an ACTIVE rental contract");
    return new ActiveContract(contract.getId(), contract.getPrinterId(), request.getOwnerSubject());
  }
}
