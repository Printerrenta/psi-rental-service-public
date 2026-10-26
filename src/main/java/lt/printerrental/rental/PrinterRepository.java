package lt.printerrental.rental;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrinterRepository extends JpaRepository<Printer, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Printer p where p.id = :id")
  Optional<Printer> lockById(@Param("id") UUID id);
}
