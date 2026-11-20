package lt.printerrental.rental;

import java.util.UUID;

public record RequestLockKey(UUID id, UUID printerId) {}
