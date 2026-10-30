package lt.printerrental.rental;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrinterRepository extends JpaRepository<Printer, UUID> {
  @org.springframework.data.jpa.repository.Modifying
  @Query(value = "DELETE FROM {h-schema}printer_locations p USING {h-schema}customer_locations l WHERE p.location_id=l.id AND p.printer_id=:printer AND l.owner_subject<>:owner", nativeQuery = true)
  void clearOtherCustomerLocation(@Param("printer") UUID printer, @Param("owner") String owner);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Printer p where p.id = :id")
  Optional<Printer> lockById(@Param("id") UUID id);
}
