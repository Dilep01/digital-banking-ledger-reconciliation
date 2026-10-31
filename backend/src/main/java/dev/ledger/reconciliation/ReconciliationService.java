package dev.ledger.reconciliation;

import dev.ledger.identity.Actor;
import dev.ledger.ledger.LedgerService;
import dev.ledger.shared.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReconciliationService {
  private final Support s;

  public ReconciliationService(Support s) {
    this.s = s;
  }

  @Transactional
  public Map<String, Object> importStatement(
      Actor actor, String source, String name, byte[] bytes) {
    actor.require("OPERATOR");
    source = Support.text(source, 60, "Source");
    if (bytes.length == 0 || bytes.length > 1048576)
      throw new ApiException(400, "INVALID_FILE", "Choose a nonempty CSV file under 1 MB.");
    String content = new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
    if (content.indexOf('\0') >= 0)
      throw new ApiException(400, "INVALID_FILE", "Only UTF-8 CSV text is accepted.");
    UUID id = UUID.randomUUID();
    String hash = Support.hash(content);
    int inserted =
        s.db.update(
            "INSERT INTO"
                + " statement_imports(id,source,file_hash,original_name,original_content,uploaded_by)"
                + " VALUES(?,?,?,?,?,?) ON CONFLICT(source,file_hash) DO NOTHING",
            id,
            source,
            hash,
            name.length() > 120 ? name.substring(0, 120) : name,
            content,
            actor.id());
    if (inserted == 0)
      return s.one(
          "SELECT id,source,original_name,created_at,true duplicate FROM statement_imports WHERE"
              + " source=? AND file_hash=?",
          source,
          hash);
    int count = 0;
    try (var parser =
        CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .get()
            .parse(new StringReader(content))) {
      if (!parser
          .getHeaderNames()
          .equals(
              List.of(
                  "external_reference",
                  "account_ref",
                  "booking_time",
                  "signed_amount",
                  "currency")))
        throw new ApiException(
            400,
            "INVALID_HEADERS",
            "Expected: external_reference,account_ref,booking_time,signed_amount,currency");
      for (CSVRecord row : parser) {
        if (++count > 2000)
          throw new ApiException(400, "TOO_MANY_ROWS", "At most 2,000 rows per import.");
        String ref = null, account = null, currency = null, error = null;
        BigDecimal amount = null;
        Timestamp booking = null;
        try {
          if (!row.isConsistent()) throw new IllegalArgumentException("Wrong column count");
          ref = Support.text(row.get("external_reference"), 100, "Reference");
          account = Support.text(row.get("account_ref"), 100, "Account");
          currency = Support.text(row.get("currency"), 10, "Currency");
          if (!currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Currency must have three uppercase letters");
          String raw = row.get("signed_amount");
          if (!raw.matches("-?[0-9]{1,7}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Invalid signed amount");
          amount = new BigDecimal(raw).setScale(2);
          if (amount.signum() == 0 || amount.abs().compareTo(new BigDecimal("1000000")) > 0)
            throw new IllegalArgumentException("Amount out of range");
          booking = Timestamp.from(Instant.parse(row.get("booking_time")));
        } catch (Exception ex) {
          error = "Invalid row: " + ex.getMessage();
          if (error.length() > 250) error = error.substring(0, 250);
        }
        s.db.update(
            "INSERT INTO"
                + " statement_rows(id,import_id,line_number,external_reference,account_ref,signed_amount,currency,booking_time,validation_error,raw_row)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            id,
            (int) row.getRecordNumber() + 1,
            ref,
            account,
            amount,
            currency,
            booking,
            error,
            row.toString());
      }
    } catch (IOException | UncheckedIOException ex) {
      throw new ApiException(
          400, "INVALID_CSV", "CSV could not be parsed. Check quoting and UTF-8 encoding.");
    }
    if (count == 0)
      throw new ApiException(
          400, "EMPTY_STATEMENT", "The statement must contain at least one data row.");
    s.audit(
        actor.id(),
        "STATEMENT_IMPORTED",
        id,
        "SUCCESS",
        "Synthetic source: " + source + "; rows: " + count);
    return s.one(
        "SELECT id,source,original_name,created_at,false duplicate FROM statement_imports WHERE"
            + " id=?",
        id);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> run(Actor actor, UUID importId, Instant start, Instant end) {
    actor.require("OPERATOR");
    LedgerService.validateRange(start, end);
    if (importId != null) s.one("SELECT id FROM statement_imports WHERE id=?", importId);
    UUID id = UUID.randomUUID();
    s.db.update(
        "INSERT INTO reconciliation_runs(id,import_id,kind,actor_id,interval_start,interval_end)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        importId,
        importId == null ? "INTERNAL" : "STATEMENT",
        actor.id(),
        Timestamp.from(start),
        Timestamp.from(end));
    int matched = 0;
    if (importId == null) internal(id);
    else {
      s.db.update(
          "INSERT INTO"
              + " reconciliation_snapshot_items(id,run_id,entry_id,reference,account_ref,signed_amount,currency)"
              + " SELECT gen_random_uuid(),?,e.id,j.reference,a.public_ref,CASE e.side WHEN"
              + " 'CREDIT' THEN e.amount ELSE -e.amount END,j.currency FROM ledger_entries e JOIN"
              + " journals j ON j.id=e.journal_id JOIN ledger_accounts a ON a.id=e.account_id WHERE"
              + " a.account_type='DEPOSIT' AND j.event_type IN ('TRANSFER','REVERSAL') AND"
              + " j.posted_at>=? AND j.posted_at<?",
          id,
          Timestamp.from(start),
          Timestamp.from(end));
      Map<String, Map<String, Object>> expected = new LinkedHashMap<>();
      for (var item : s.rows("SELECT * FROM reconciliation_snapshot_items WHERE run_id=?", id))
        expected.put(key(item, "reference"), item);
      Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
      for (var row :
          s.rows("SELECT * FROM statement_rows WHERE import_id=? ORDER BY line_number", importId)) {
        if (row.get("validation_error") != null) {
          finding(
              id,
              "INVALID_ROW",
              String.valueOf(row.get("external_reference")),
              "Line " + row.get("line_number") + ": " + row.get("validation_error"),
              Support.uuid(row.get("id")),
              null);
          continue;
        }
        Instant time = Support.instant(row.get("booking_time"));
        if (time.isBefore(start) || !time.isBefore(end)) {
          finding(
              id,
              "OUTSIDE_INTERVAL",
              row.get("external_reference").toString(),
              "Statement booking time is outside the selected interval.",
              Support.uuid(row.get("id")),
              null);
          continue;
        }
        groups.computeIfAbsent(key(row, "external_reference"), k -> new ArrayList<>()).add(row);
      }
      for (var group : groups.entrySet()) {
        var row = group.getValue().getFirst();
        var item = expected.remove(group.getKey());
        UUID rowId = Support.uuid(row.get("id")),
            itemId = item == null ? null : Support.uuid(item.get("id"));
        String ref = row.get("external_reference").toString();
        if (group.getValue().size() > 1) {
          finding(
              id,
              "DUPLICATE_STATEMENT_LEG",
              ref,
              "Repeated reference/account leg at lines "
                  + group.getValue().stream().map(r -> r.get("line_number").toString()).toList(),
              rowId,
              itemId);
          continue;
        }
        if (item == null) {
          finding(
              id,
              "STATEMENT_ONLY",
              ref,
              "No internal transfer/reversal leg for account " + row.get("account_ref"),
              rowId,
              null);
          continue;
        }
        BigDecimal actual = new BigDecimal(row.get("signed_amount").toString()),
            wanted = new BigDecimal(item.get("signed_amount").toString());
        if (!row.get("currency").equals(item.get("currency")))
          finding(
              id,
              "CURRENCY_MISMATCH",
              ref,
              "Expected " + item.get("currency") + "; statement has " + row.get("currency"),
              rowId,
              itemId);
        else if (actual.signum() != wanted.signum())
          finding(
              id,
              "DIRECTION_MISMATCH",
              ref,
              "Expected " + wanted + "; statement has " + actual,
              rowId,
              itemId);
        else if (actual.compareTo(wanted) != 0)
          finding(
              id,
              "AMOUNT_MISMATCH",
              ref,
              "Expected " + wanted + "; statement has " + actual,
              rowId,
              itemId);
        else matched++;
      }
      for (var item : expected.values())
        finding(
            id,
            "MISSING_STATEMENT_LEG",
            item.get("reference").toString(),
            "No statement leg for account " + item.get("account_ref"),
            null,
            Support.uuid(item.get("id")));
    }
    int count =
        s.db.queryForObject(
            "SELECT count(*) FROM reconciliation_exceptions WHERE run_id=?", Integer.class, id);
    s.db.update(
        "UPDATE reconciliation_runs SET matched_count=?,exception_count=? WHERE id=?",
        matched,
        count,
        id);
    s.audit(actor.id(), "RECONCILIATION_RUN", id, "SUCCESS", "Findings: " + count);
    return s.one("SELECT * FROM reconciliation_runs WHERE id=?", id);
  }

  private static String key(Map<String, Object> row, String reference) {
    return row.get(reference) + "|" + row.get("account_ref");
  }

  private void internal(UUID run) {
    for (var row :
        s.rows(
            "SELECT j.id,j.reference,count(e.id) entries,coalesce(sum(CASE e.side WHEN 'DEBIT' THEN"
                + " e.amount ELSE -e.amount END),0) net FROM journals j LEFT JOIN ledger_entries e"
                + " ON e.journal_id=j.id GROUP BY j.id HAVING count(e.id)<2 OR coalesce(sum(CASE"
                + " e.side WHEN 'DEBIT' THEN e.amount ELSE -e.amount END),0)<>0"))
      finding(run, "UNBALANCED_JOURNAL", row.get("reference").toString(), s.json(row), null, null);
    for (var row :
        s.rows(
            "SELECT a.id,a.public_ref,b.balance,coalesce(sum(CASE WHEN e.side=a.normal_side THEN"
                + " e.amount ELSE -e.amount END),0) computed FROM ledger_accounts a JOIN"
                + " account_balances b ON b.account_id=a.id LEFT JOIN ledger_entries e ON"
                + " e.account_id=a.id GROUP BY a.id,b.balance HAVING b.balance<>coalesce(sum(CASE"
                + " WHEN e.side=a.normal_side THEN e.amount ELSE -e.amount END),0) OR b.balance<0"))
      finding(run, "BALANCE_MISMATCH", row.get("public_ref").toString(), s.json(row), null, null);
    for (var row :
        s.rows(
            "SELECT t.id,j.reference FROM transfers t JOIN journals j ON j.id=t.journal_id WHERE"
                + " j.event_type<>'TRANSFER' OR (SELECT count(*) FROM ledger_entries e WHERE"
                + " e.journal_id=j.id)<>2 OR NOT EXISTS(SELECT 1 FROM ledger_entries e WHERE"
                + " e.journal_id=j.id AND e.account_id=t.source_id AND e.side='DEBIT' AND"
                + " e.amount=t.amount) OR NOT EXISTS(SELECT 1 FROM ledger_entries e WHERE"
                + " e.journal_id=j.id AND e.account_id=t.destination_id AND e.side='CREDIT' AND"
                + " e.amount=t.amount)"))
      finding(
          run, "TRANSFER_LEG_MISMATCH", row.get("reference").toString(), s.json(row), null, null);
    for (var row :
        s.rows(
            "SELECT r.id,r.reference FROM journals r WHERE r.event_type='REVERSAL' AND"
                + " EXISTS(SELECT 1 FROM ledger_entries original WHERE"
                + " original.journal_id=r.reversal_of AND NOT EXISTS(SELECT 1 FROM ledger_entries"
                + " reversed WHERE reversed.journal_id=r.id AND"
                + " reversed.account_id=original.account_id AND reversed.side<>original.side AND"
                + " reversed.amount=original.amount))"))
      finding(run, "INVALID_REVERSAL", row.get("reference").toString(), s.json(row), null, null);
  }

  private void finding(
      UUID run, String category, String reference, String detail, UUID row, UUID item) {
    s.db.update(
        "INSERT INTO"
            + " reconciliation_exceptions(id,run_id,category,reference,detail,statement_row_id,snapshot_item_id)"
            + " VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        run,
        category,
        reference,
        detail,
        row,
        item);
  }

  public Map<String, Object> detail(Actor actor, UUID id) {
    actor.require("OPERATOR", "APPROVER", "AUDITOR");
    var run = new LinkedHashMap<>(s.one("SELECT * FROM reconciliation_runs WHERE id=?", id));
    run.put(
        "exceptions",
        s.rows(
            "SELECT * FROM reconciliation_exceptions WHERE run_id=? ORDER BY created_at,id", id));
    run.put(
        "snapshot",
        s.rows(
            "SELECT * FROM reconciliation_snapshot_items WHERE run_id=? ORDER BY"
                + " reference,account_ref",
            id));
    return run;
  }

  public Map<String, Object> exceptionDetail(Actor actor, UUID id) {
    actor.require("OPERATOR", "APPROVER", "AUDITOR");
    var item = new LinkedHashMap<>(s.one("SELECT * FROM reconciliation_exceptions WHERE id=?", id));
    item.put(
        "events",
        s.rows(
            "SELECT e.*,u.display_name actor_name FROM exception_events e JOIN users u ON"
                + " u.id=e.actor_id WHERE exception_id=? ORDER BY created_at,id",
            id));
    if (item.get("statement_row_id") != null)
      item.put(
          "statement_row",
          s.one(
              "SELECT * FROM statement_rows WHERE id=?",
              Support.uuid(item.get("statement_row_id"))));
    if (item.get("snapshot_item_id") != null)
      item.put(
          "internal_leg",
          s.one(
              "SELECT si.*,e.journal_id FROM reconciliation_snapshot_items si JOIN ledger_entries e"
                  + " ON e.id=si.entry_id WHERE si.id=?",
              Support.uuid(item.get("snapshot_item_id"))));
    return item;
  }

  @Transactional
  public void event(Actor actor, UUID id, String state, String note) {
    actor.require("OPERATOR");
    note = Support.text(note, 500, "Investigation note");
    var item = s.one("SELECT * FROM reconciliation_exceptions WHERE id=? FOR UPDATE", id);
    String old = item.get("state").toString();
    boolean allowed =
        (old.equals("OPEN") && Set.of("INVESTIGATING", "DISMISSED").contains(state))
            || (old.equals("INVESTIGATING")
                && Set.of("INVESTIGATING", "DISMISSED").contains(state));
    if (!allowed)
      throw new ApiException(
          409,
          "INVALID_TRANSITION",
          "Start an investigation or dismiss with a reason. Financial resolution requires an"
              + " approved correction.");
    s.db.update("UPDATE reconciliation_exceptions SET state=? WHERE id=?", state, id);
    s.db.update(
        "INSERT INTO exception_events(id,exception_id,actor_id,state,note) VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        actor.id(),
        state,
        note);
    s.audit(actor.id(), "EXCEPTION_" + state, id, "SUCCESS", note);
  }
}
