package lt.printerrental.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountingRepository {
  public record Settings(boolean a3Capable, List<Long> baseline, long revision) {}

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final String settingsTable, termsTable;

  public AccountingRepository(
      JdbcTemplate db,
      ObjectMapper json,
      @org.springframework.beans.factory.annotation.Value("${DB_SCHEMA:rental}") String schema) {
    if (!schema.matches("[a-z][a-z0-9_]*"))
      throw new IllegalArgumentException("Invalid service schema");
    this.db = db;
    this.json = json;
    settingsTable = schema + ".contract_accounting";
    termsTable = schema + ".contract_terms";
  }

  public Optional<Settings> find(UUID id) {
    return db
        .query(
            "SELECT a3_capable,baseline,revision FROM " + settingsTable + " WHERE contract_id=?",
            (r, n) -> {
              try {
                return new Settings(
                    r.getBoolean(1),
                    json.readValue(
                        r.getString(2),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {}),
                    r.getLong(3));
              } catch (Exception e) {
                throw new java.sql.SQLException("Invalid accounting baseline", e);
              }
            },
            id)
        .stream()
        .findFirst();
  }

  public void initialize(UUID id, boolean a3, List<Long> baseline) {
    try {
      db.update(
          "INSERT INTO " + settingsTable + "(contract_id,a3_capable,baseline) VALUES(?,?,?::jsonb)",
          id,
          a3,
          json.writeValueAsString(baseline));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid baseline");
    }
  }

  public List<AccountingSnapshot.Terms> terms(UUID id) {
    return db.query(
        "SELECT id,effective_from,monthly_fee,mono_price,color_price,vat_percent,payment_term_days"
            + " FROM "
            + termsTable
            + " WHERE contract_id=? ORDER BY effective_from",
        (r, n) ->
            new AccountingSnapshot.Terms(
                r.getObject(1, UUID.class),
                r.getObject(2, LocalDate.class),
                r.getBigDecimal(3),
                r.getBigDecimal(4),
                r.getBigDecimal(5),
                r.getBigDecimal(6),
                r.getInt(7)),
        id);
  }

  public void add(UUID contract, AccountingSnapshot.Terms t, String actor) {
    db.update(
        "INSERT INTO "
            + termsTable
            + "(id,contract_id,effective_from,monthly_fee,mono_price,color_price,vat_percent,payment_term_days,actor)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        t.id(),
        contract,
        t.effectiveFrom(),
        t.monthlyFee(),
        t.monoPrice(),
        t.colorPrice(),
        t.vatPercent(),
        t.paymentTermDays(),
        actor);
  }

  public void bump(UUID id) {
    db.update("UPDATE " + settingsTable + " SET revision=revision+1 WHERE contract_id=?", id);
  }
}
