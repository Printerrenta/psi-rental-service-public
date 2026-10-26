package lt.printerrental.rental;

/** Persisted names are the existing SQL/API contract; no schema migration is needed. */
public enum ContractStatus {
  DRAFT,
  ACTIVE,
  COMPLETED
}
