package lt.printerrental.rental;

import jakarta.validation.constraints.*;
import java.util.*;
import lt.printerrental.auth.CurrentUser;
import lt.printerrental.auth.RentalAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LocationService {
  private final JdbcTemplate jdbc;
  private final CurrentUser user;
  private final RentalAccessPolicy access;
  private final RentalRequestService requests;
  private final String schema;

  public LocationService(JdbcTemplate jdbc, CurrentUser user, RentalAccessPolicy access,
      RentalRequestService requests, @org.springframework.beans.factory.annotation.Value("${DB_SCHEMA:rental}") String schema) {
    if (!schema.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid service schema");
    this.schema=schema;
    this.jdbc = jdbc; this.user = user; this.access = access; this.requests = requests;
  }

  private String sql(String query) {
    for (String table : List.of("customer_locations", "printer_locations", "rental_request_locations", "rental_requests", "rental_contracts", "printers"))
      query=query.replaceAll("\\b"+table+"\\b",schema+"."+table);
    return query;
  }

  public record Input(@NotBlank @Size(max=120) String name,
      @NotBlank @Size(max=255) String address, @NotBlank @Size(max=120) String city,
      @Size(max=255) String ownerSubject) {}
  public record Update(@NotNull @PositiveOrZero Long version,
      @NotBlank @Size(max=120) String name, @NotBlank @Size(max=255) String address,
      @NotBlank @Size(max=120) String city) {}
  public record Placement(UUID printerId, long version) {}
  public record Location(UUID id, String ownerSubject, String name, String address,
      String city, long version, List<Placement> printers) {}
  public record LocationPage(List<Location> content, int page, int size, long totalElements) {}
  public record Assignment(@NotNull UUID locationId, @NotNull @PositiveOrZero Long version) {}
  public record LocatedRequest(@jakarta.validation.Valid @NotNull CreateRentalRequest request,
      @NotNull UUID locationId) {}
  public record RequestUpdate(@jakarta.validation.Valid @NotNull CreateRentalRequest request,
      @NotNull UUID locationId, @NotNull @PositiveOrZero Long version) {}
  public record RequestView(RentalResponse request, UUID locationId) {}
  public record RequestPage(List<RequestView> content, int page, int size, long totalElements) {}

  private Location map(java.sql.ResultSet rs) throws java.sql.SQLException {
    UUID id = rs.getObject("id", UUID.class);
    var placements = jdbc.query(sql("SELECT printer_id, version FROM printer_locations WHERE location_id=? ORDER BY printer_id"),
        (r,n) -> new Placement(r.getObject(1,UUID.class), r.getLong(2)), id);
    return new Location(id, rs.getString("owner_subject"), rs.getString("name"),
        rs.getString("address"), rs.getString("city"), rs.getLong("version"), placements);
  }
  public Location get(UUID id) {
    var rows = jdbc.query(sql("SELECT * FROM customer_locations WHERE id=?"), (r,n) -> map(r), id);
    if (rows.isEmpty()) throw new IllegalArgumentException("Lokacija nerasta.");
    var location = rows.getFirst(); access.requireOwner(location.ownerSubject()); return location;
  }
  public LocationPage list(int page, int size) {
    String condition = user.isStaff() ? "" : " WHERE owner_subject=?";
    var args = new ArrayList<Object>(); if (!user.isStaff()) args.add(user.subject());
    long count = jdbc.queryForObject(sql("SELECT count(*) FROM customer_locations")+condition, Long.class, args.toArray());
    args.add(size); args.add((long) page*size);
    var rows = jdbc.query(sql("SELECT * FROM customer_locations")+condition+" ORDER BY created_at,id LIMIT ? OFFSET ?",
        (r,n) -> map(r), args.toArray());
    return new LocationPage(rows,page,size,count);
  }
  @Transactional
  public Location create(Input input) {
    String owner = input.ownerSubject() == null ? user.subject() : input.ownerSubject().strip();
    if (owner.isBlank()) throw new IllegalArgumentException("Pasirinkite klientą.");
    access.requireOwner(owner);
    UUID id = UUID.randomUUID();
    jdbc.update(sql("INSERT INTO customer_locations(id,owner_subject,name,address,city) VALUES(?,?,?,?,?)"),
        id,owner,input.name().strip(),input.address().strip(),input.city().strip());
    return get(id);
  }
  @Transactional
  public Location update(UUID id, Update input) {
    get(id);
    int changed = jdbc.update(sql("UPDATE customer_locations SET name=?,address=?,city=?,version=version+1 WHERE id=? AND version=?"),
        input.name().strip(),input.address().strip(),input.city().strip(),id,input.version());
    if (changed != 1) throw new RentalConflictException("Lokacija pasikeitė. Atnaujinkite duomenis.");
    return get(id);
  }
  @Transactional
  public Location assign(UUID printer, Assignment input) {
    access.requireStaff();
    var location = get(input.locationId());
    var locked = jdbc.queryForList(sql("SELECT id FROM printers WHERE id=? FOR UPDATE"),UUID.class,printer);
    if (locked.isEmpty()) throw new PrinterNotFoundException(printer);
    long incompatible = jdbc.queryForObject(sql("""
        SELECT count(*) FROM rental_contracts c JOIN rental_requests r ON c.rental_request_id=r.id
        WHERE c.printer_id=? AND r.owner_subject<>? AND r.status<>'CANCELLED' AND
        (c.status='ACTIVE' OR (c.status='DRAFT' AND c.end_date>=CURRENT_DATE))
        """),Long.class,printer,location.ownerSubject());
    if (incompatible > 0) throw new RentalConflictException("Spausdintuvas priskirtas kitam klientui.");
    var versions = jdbc.queryForList(sql("SELECT version FROM printer_locations WHERE printer_id=?"),Long.class,printer);
    if (versions.isEmpty()) {
      if (input.version()!=0) throw new RentalConflictException("Priskyrimas pasikeitė.");
      jdbc.update(sql("INSERT INTO printer_locations(printer_id,location_id,version) VALUES(?,?,1)"),printer,location.id());
    } else {
      int changed=jdbc.update(sql("UPDATE printer_locations SET location_id=?,version=version+1 WHERE printer_id=? AND version=?"),
          location.id(),printer,input.version());
      if (changed!=1) throw new RentalConflictException("Priskyrimas pasikeitė. Atnaujinkite duomenis.");
    }
    return get(location.id());
  }
  @Transactional
  public RequestView createRequest(LocatedRequest input) {
    if (input.request().ownerSubject()!=null) throw new IllegalArgumentException("Šioje formoje paraiškos savininkas nustatomas pagal prisijungusią paskyrą.");
    var location = get(input.locationId());
    // A submitted request belongs to the authenticated customer, never a supplied email.
    if (!location.ownerSubject().equals(user.subject()))
      throw new IllegalArgumentException("Paraiškai pasirinkite savo lokaciją.");
    var request = requests.create(input.request());
    jdbc.update(sql("INSERT INTO rental_request_locations(request_id,location_id) VALUES(?,?)"),request.id(),location.id());
    return new RequestView(request,location.id());
  }
  public RequestPage listRequests(int page,int size) {
    var result=requests.list(null,page,size);
    var rows=result.getContent().stream().map(r -> {
      var ids=jdbc.queryForList(sql("SELECT location_id FROM rental_request_locations WHERE request_id=?"),UUID.class,r.id());
      return new RequestView(r,ids.isEmpty()?null:ids.getFirst());
    }).toList();
    return new RequestPage(rows,page,size,result.getTotalElements());
  }
  @Transactional
  public void updateRequest(UUID id, RequestUpdate input) {
    if (input.request().ownerSubject()!=null) throw new IllegalArgumentException("Paraiškos savininko keisti negalima.");
    requests.get(id); // Enforces request ownership before any mutation.
    var owner=jdbc.queryForObject(sql("SELECT owner_subject FROM rental_requests WHERE id=?"),String.class,id);
    var location=get(input.locationId());
    if (!location.ownerSubject().equals(owner)) throw new IllegalArgumentException("Lokacija turi priklausyti paraiškos klientui.");
    var r=input.request();
    if (r.startDate().isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC)) || r.endDate().isBefore(r.startDate()))
      throw new IllegalArgumentException("Patikrinkite pageidaujamą nuomos laikotarpį.");
    int changed=jdbc.update(sql("UPDATE rental_requests SET customer_name=?,customer_email=?,estimated_monthly_pages=?,color_printing_required=?,start_date=?,end_date=?,notes=?,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=? AND version=? AND status='PENDING'"),
        r.customerName().strip(),r.customerEmail().strip(),r.estimatedMonthlyPages(),r.colorPrintingRequired(),r.startDate(),r.endDate(),r.notes()==null?null:r.notes().strip(),id,input.version());
    if (changed!=1) throw new RentalConflictException("Paraiška pasikeitė arba jau nagrinėjama. Atnaujinkite duomenis.");
    jdbc.update(sql("INSERT INTO rental_request_locations(request_id,location_id) VALUES(?,?) ON CONFLICT(request_id) DO UPDATE SET location_id=excluded.location_id"),id,location.id());
  }
}
