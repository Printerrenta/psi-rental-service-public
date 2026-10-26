package lt.printerrental.rental;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import lt.printerrental.auth.CurrentUser;
import lt.printerrental.auth.RentalAccessPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RentalRequestService {
  private final RentalRequestRepository repository;
  private final Clock clock;
  private final lt.printerrental.events.EventRecorder events;
  private final CurrentUser currentUser;
  private final RentalAccessPolicy access;

  public RentalRequestService(
      RentalRequestRepository repository,
      Clock clock,
      CurrentUser currentUser,
      RentalAccessPolicy access,
      lt.printerrental.events.EventRecorder events) {
    this.repository = repository;
    this.clock = clock;
    this.events = events;
    this.currentUser = currentUser;
    this.access = access;
  }

  @Transactional
  public RentalResponse create(CreateRentalRequest input) {
    if (input.startDate().isBefore(LocalDate.now(clock))) {
      throw new IllegalArgumentException("startDate must be today or later (UTC)");
    }
    if (input.endDate().isBefore(input.startDate())) {
      throw new IllegalArgumentException("endDate must be on or after startDate");
    }
    var request = new RentalRequest(input, clock.instant());
    request.setOwnerSubject(currentUser.subject());
    var saved = repository.saveAndFlush(request);
    events.record(
        "RentalRequestCreated",
        saved.getId(),
        saved.getOwnerSubject(),
        java.util.Map.of("status", saved.getStatus().name()));
    return RentalResponse.from(saved);
  }

  public RentalResponse get(UUID id) {
    var request = find(id);
    access.requireOwner(request.getOwnerSubject());
    return RentalResponse.from(request);
  }

  public Page<RentalResponse> list(RentalStatus status, int page, int size) {
    var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    var result =
        currentUser.isStaff()
            ? (status == null
                ? repository.findAll(pageable)
                : repository.findByStatus(status, pageable))
            : (status == null
                ? repository.findByOwnerSubject(currentUser.subject(), pageable)
                : repository.findByOwnerSubjectAndStatus(currentUser.subject(), status, pageable));
    return result.map(RentalResponse::from);
  }

  @Transactional
  public RentalResponse changeStatus(UUID id, ChangeRentalStatus input) {
    access.requireStaff();
    var request = repository.lockById(id).orElseThrow(() -> new RentalNotFoundException(id));
    if (!input.version().equals(request.getVersion())) {
      throw new RentalConflictException("Request changed; reload it and use its current version");
    }
    if (input.status() == RentalStatus.APPROVED && request.getPrinterId() == null) {
      throw new RentalConflictException("Assign a catalog printer before approving the request");
    }
    if (repository.hasActiveContract(id)) {
      throw new RentalConflictException(
          "An active contract exists; request status cannot be changed independently");
    }
    request.transitionTo(input.status(), clock.instant());
    var saved = repository.saveAndFlush(request);
    events.record(
        "RentalStatusChanged",
        saved.getId(),
        saved.getOwnerSubject(),
        java.util.Map.of("status", saved.getStatus().name()));
    return RentalResponse.from(saved);
  }

  private RentalRequest find(UUID id) {
    return repository.findById(id).orElseThrow(() -> new RentalNotFoundException(id));
  }
}
