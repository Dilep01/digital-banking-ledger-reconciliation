package dev.ledger.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Support {
  public final JdbcTemplate db;
  private final ObjectMapper mapper;

  public Support(JdbcTemplate db, ObjectMapper mapper) {
    this.db = db;
    this.mapper = mapper;
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> parse(String value) {
    try {
      return mapper.readValue(value, Map.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static UUID uuid(Object value) {
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (Exception ex) {
      throw new ApiException(400, "INVALID_ID", "A valid identifier is required.");
    }
  }

  public static String text(Object value, int maximum, String name) {
    String s = value == null ? "" : value.toString().strip();
    if (s.isEmpty() || s.length() > maximum)
      throw new ApiException(
          400, "INVALID_FIELD", name + " is required (maximum " + maximum + " characters).");
    return s;
  }

  public static BigDecimal money(String value) {
    if (value == null || !value.matches("[0-9]{1,7}(\\.[0-9]{1,2})?"))
      throw new ApiException(
          400, "INVALID_AMOUNT", "Use an INR amount with at most two decimal places.");
    BigDecimal amount = new BigDecimal(value).setScale(2);
    if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("1000000.00")) > 0)
      throw new ApiException(
          400, "INVALID_AMOUNT", "Amount must be between 0.01 and 1,000,000.00.");
    return amount;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static Instant instant(Object value) {
    try {
      return Instant.parse(String.valueOf(value));
    } catch (Exception ex) {
      throw new ApiException(
          400, "INVALID_TIME", "Use a UTC ISO timestamp, such as 2026-10-28T09:30:00Z.");
    }
  }

  public void audit(UUID actor, String action, Object target, String outcome, String reason) {
    String correlation = org.slf4j.MDC.get("correlationId");
    db.update(
        "INSERT INTO audit_events(id,actor_id,action,target,outcome,reason,correlation_id)"
            + " VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        actor,
        action,
        String.valueOf(target),
        outcome,
        reason,
        correlation == null ? "background" : correlation);
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return db.query(
        sql,
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof BigDecimal b) v = b.toPlainString();
            else if (v instanceof java.sql.Timestamp t) v = t.toInstant().toString();
            else if (v instanceof java.time.OffsetDateTime t) v = t.toInstant().toString();
            else if (v instanceof UUID u) v = u.toString();
            row.put(rs.getMetaData().getColumnLabel(i), v);
          }
          return row;
        },
        args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }
}
