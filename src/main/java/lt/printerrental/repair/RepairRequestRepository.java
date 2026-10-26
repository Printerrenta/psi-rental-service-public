package lt.printerrental.repair;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RepairRequestRepository
    extends JpaRepository<RepairRequest, UUID>, JpaSpecificationExecutor<RepairRequest> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RepairRequest r where r.id = :id")
  Optional<RepairRequest> lockById(@Param("id") UUID id);

  boolean existsByContractIdAndStatusIn(UUID contractId, Collection<RepairStatus> statuses);

  default Page<RepairRequest> search(
      String owner, UUID contractId, RepairStatus status, Pageable page) {
    Specification<RepairRequest> filter =
        (root, query, cb) -> {
          var terms = new ArrayList<jakarta.persistence.criteria.Predicate>();
          if (owner != null) terms.add(cb.equal(root.get("ownerSubject"), owner));
          if (contractId != null) terms.add(cb.equal(root.get("contractId"), contractId));
          if (status != null) terms.add(cb.equal(root.get("status"), status));
          return cb.and(terms.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    return findAll(filter, page);
  }
}
