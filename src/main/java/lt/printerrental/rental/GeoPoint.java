package lt.printerrental.rental;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;

/** WGS84 map position of a customer location, stored with ~10 cm precision. */
public record GeoPoint(
    @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
    @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {

  private static final int SCALE = 6;

  public GeoPoint {
    if (latitude == null || longitude == null) throw new IllegalArgumentException("Nurodykite abi koordinates.");
    if (latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0 || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0)
      throw new IllegalArgumentException("Koordinatės už leistinų ribų.");
    latitude = latitude.setScale(SCALE, RoundingMode.HALF_UP);
    longitude = longitude.setScale(SCALE, RoundingMode.HALF_UP);
  }

  /** Reads the optional position columns; a location without coordinates yields {@code null}. */
  static GeoPoint from(ResultSet rs) throws SQLException {
    BigDecimal lat = rs.getBigDecimal("latitude");
    BigDecimal lng = rs.getBigDecimal("longitude");
    return lat == null || lng == null ? null : new GeoPoint(lat, lng);
  }

  static BigDecimal latitudeOf(GeoPoint point) { return point == null ? null : point.latitude(); }
  static BigDecimal longitudeOf(GeoPoint point) { return point == null ? null : point.longitude(); }
}
