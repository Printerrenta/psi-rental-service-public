package lt.printerrental.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public record AccountingSnapshot(
    UUID contractId,
    UUID printerId,
    String ownerSubject,
    String modelKey,
    boolean colorCapable,
    boolean a3Capable,
    String status,
    LocalDate startDate,
    LocalDate endDate,
    LocalDate completedReadingDate,
    LocalDate baselineDate,
    List<Long> baseline,
    long revision,
    List<Terms> terms) {
  public record Terms(
      UUID id,
      LocalDate effectiveFrom,
      BigDecimal monthlyFee,
      BigDecimal monoPrice,
      BigDecimal colorPrice,
      BigDecimal vatPercent,
      int paymentTermDays) {}

  public AccountingSnapshot {
    baseline = List.copyOf(baseline);
    terms = List.copyOf(terms);
  }
}
