package lt.printerrental.rental;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<RentalContract, UUID> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select c from RentalContract c where c.id = :id")
  java.util.Optional<RentalContract> lockForAccounting(
      @org.springframework.data.repository.query.Param("id") java.util.UUID id);

  @org.springframework.data.jpa.repository.Query(
      "select c from RentalContract c, RentalRequest r where c.rentalRequestId=r.id and"
          + " r.ownerSubject=:owner")
  org.springframework.data.domain.Page<RentalContract> findOwned(
      @org.springframework.data.repository.query.Param("owner") String owner,
      org.springframework.data.domain.Pageable pageable);

  // A shared row lock serializes repair creation against contract completion.
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
  @org.springframework.data.jpa.repository.Query("select c from RentalContract c where c.id = :id")
  java.util.Optional<RentalContract> findForRepair(
      @org.springframework.data.repository.query.Param("id") UUID id);

  boolean existsByRentalRequestId(UUID rentalRequestId);
}
