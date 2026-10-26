package lt.printerrental.repair;

import java.time.Clock;
import java.util.*;
import lt.printerrental.auth.CurrentUser;
import lt.printerrental.auth.RentalAccessPolicy;
import lt.printerrental.events.EventRecorder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RepairRequestService {
  private final RepairRequestRepository repairs;
  private final RepairContractAccess contracts;
  private final CurrentUser user;
  private final RentalAccessPolicy access;
  private final EventRecorder events;
  private final Clock clock;

  public RepairRequestService(
      RepairRequestRepository repairs,
      RepairContractAccess contracts,
      CurrentUser user,
      RentalAccessPolicy access,
      EventRecorder events,
      Clock clock) {
    this.repairs = repairs;
    this.contracts = contracts;
    this.user = user;
    this.access = access;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public RepairResponse create(CreateRepairRequest input) {
    var contract = contracts.requireActive(input.contractId());
    if (repairs.existsByContractIdAndStatusIn(
        contract.contractId(), List.of(RepairStatus.OPEN, RepairStatus.IN_PROGRESS)))
      throw new RepairConflictException("An open repair request already exists for this contract");
    var request = new RepairRequest(contract, input.description(), clock.instant());
    try {
      request = repairs.saveAndFlush(request);
    } catch (DataIntegrityViolationException error) {
      if (String.valueOf(error.getMostSpecificCause().getMessage())
          .contains("repair_requests_one_open_contract"))
        throw new RepairConflictException(
            "An open repair request already exists for this contract");
      throw error;
    }
    record(request, "RepairRequestCreated");
    return RepairResponse.from(request);
  }

  public RepairResponse get(UUID id) {
    var request = repairs.findById(id).orElseThrow(() -> new RepairNotFoundException(id));
    access.requireOwner(request.getOwnerSubject());
    return RepairResponse.from(request);
  }

  public Page<RepairResponse> list(UUID contractId, RepairStatus status, int page, int size) {
    var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    return repairs
        .search(user.isStaff() ? null : user.subject(), contractId, status, pageable)
        .map(RepairResponse::from);
  }

  @Transactional
  public RepairResponse changeStatus(UUID id, ChangeRepairStatus input) {
    var request = repairs.lockById(id).orElseThrow(() -> new RepairNotFoundException(id));
    access.requireOwner(request.getOwnerSubject());
    if (!user.isStaff()
        && (input.status() != RepairStatus.CANCELLED || request.getStatus() != RepairStatus.OPEN))
      throw new AccessDeniedException("Customers can only cancel their own OPEN repair requests");
    if (!Objects.equals(input.version(), request.getVersion()))
      throw new RepairConflictException(
          "Repair request changed; reload it and use its current version");
    request.transitionTo(input.status(), input.resolution(), clock.instant());
    var saved = repairs.saveAndFlush(request);
    record(saved, "RepairStatusChanged");
    return RepairResponse.from(saved);
  }

  private void record(RepairRequest request, String type) {
    events.record(
        type,
        request.getId(),
        request.getOwnerSubject(),
        Map.of(
            "status",
            request.getStatus().name(),
            "contractId",
            request.getContractId().toString(),
            "printerId",
            request.getPrinterId().toString()));
  }
}
