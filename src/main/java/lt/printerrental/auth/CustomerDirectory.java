package lt.printerrental.auth;

/** Resolve a staff-selected customer through the identity service, never by contact email. */
public interface CustomerDirectory {
  AppUser get(String subject);
}
