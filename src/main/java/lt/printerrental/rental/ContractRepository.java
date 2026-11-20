package lt.printerrental.rental;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractRepository extends JpaRepository<RentalContract, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from RentalContract c where c.id = :id")
  Optional<RentalContract> lockById(@Param("id") UUID id);

  @Query(
      "select new lt.printerrental.rental.ContractLockKeys(c.printerId, c.rentalRequestId) "
          + "from RentalContract c where c.id = :id")
  Optional<ContractLockKeys> findLockKeysById(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from RentalContract c where c.id = :id")
  Optional<RentalContract> lockForAccounting(@Param("id") UUID id);

  @org.springframework.data.jpa.repository.Query(
      "select c from RentalContract c, RentalRequest r where c.rentalRequestId=r.id and"
          + " (c.status <> 'DRAFT' or r.status <> 'CANCELLED')")
  org.springframework.data.domain.Page<RentalContract> findVisible(
      org.springframework.data.domain.Pageable pageable);

  @org.springframework.data.jpa.repository.Query(
      "select c from RentalContract c, RentalRequest r where c.rentalRequestId=r.id and"
          + " r.ownerSubject=:owner and (c.status <> 'DRAFT' or r.status <> 'CANCELLED')")
  org.springframework.data.domain.Page<RentalContract> findOwned(
      @org.springframework.data.repository.query.Param("owner") String owner,
      org.springframework.data.domain.Pageable pageable);

  // A shared row lock serializes repair creation against contract completion.
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
  @org.springframework.data.jpa.repository.Query("select c from RentalContract c where c.id = :id")
  java.util.Optional<RentalContract> findForRepair(
      @org.springframework.data.repository.query.Param("id") UUID id);

  boolean existsByRentalRequestId(UUID rentalRequestId);

  @org.springframework.data.jpa.repository.Query("""
      select (count(c) > 0) from RentalContract c, RentalRequest r
      where c.rentalRequestId = r.id and c.printerId = :printerId
      and c.rentalRequestId <> :requestId and c.status in ('DRAFT', 'ACTIVE')
      and r.status <> 'CANCELLED' and c.startDate <= :endDate and c.endDate >= :startDate
      """)
  boolean hasPrinterOverlap(
      @org.springframework.data.repository.query.Param("printerId") UUID printerId,
      @org.springframework.data.repository.query.Param("requestId") UUID requestId,
      @org.springframework.data.repository.query.Param("startDate") java.time.LocalDate startDate,
      @org.springframework.data.repository.query.Param("endDate") java.time.LocalDate endDate);
}
