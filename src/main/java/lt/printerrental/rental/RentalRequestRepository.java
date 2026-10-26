package lt.printerrental.rental;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalRequestRepository extends JpaRepository<RentalRequest, UUID> {
  @org.springframework.data.jpa.repository.Query(
      """
      select (count(c) > 0) from RentalContract c
      where c.rentalRequestId = :id and c.status = 'ACTIVE'
      """)
  boolean hasActiveContract(@org.springframework.data.repository.query.Param("id") UUID id);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select r from RentalRequest r where r.id = :id")
  java.util.Optional<RentalRequest> lockById(
      @org.springframework.data.repository.query.Param("id") UUID id);

  @org.springframework.data.jpa.repository.Query(
      """
      select (count(r) > 0) from RentalRequest r
      where r.printerId = :printerId and r.id <> :requestId
      and r.status in :statuses and r.startDate <= :endDate and r.endDate >= :startDate
      """)
  boolean hasOverlap(
      @org.springframework.data.repository.query.Param("printerId") UUID printerId,
      @org.springframework.data.repository.query.Param("requestId") UUID requestId,
      @org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate,
      @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate,
      @org.springframework.data.repository.query.Param("statuses")
          java.util.List<RentalStatus> statuses);

  Page<RentalRequest> findByOwnerSubject(String ownerSubject, Pageable pageable);

  Page<RentalRequest> findByOwnerSubjectAndStatus(
      String ownerSubject, RentalStatus status, Pageable pageable);

  Page<RentalRequest> findByStatus(RentalStatus status, Pageable pageable);
}
