package lt.printerrental.rental;

import java.util.UUID;

public record ContractLockKeys(UUID printerId, UUID rentalRequestId) {}
