package dev.ledger.ledger;

import dev.ledger.identity.Actor;
import dev.ledger.shared.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
  private final Support s;
  private final PostingProbe probe;
  public static final UUID CASH = UUID.fromString("00000000-0000-0000-0000-000000000001");

  public LedgerService(Support s, PostingProbe probe) {
    this.s = s;
    this.probe = probe;
  }

  public record TransferInput(
      String sourceAccountId,
      String destinationAccountRef,
      String amount,
      String currency,
      String description) {}

  public record Result(int status, Map<String, Object> body) {}

  public List<Map<String, Object>> accounts(Actor actor) {
    String sql =
        "SELECT a.*,b.balance FROM ledger_accounts a JOIN account_balances b ON b.account_id=a.id"
            + " WHERE a.account_type='DEPOSIT'";
    return actor.customer()
        ? s.rows(sql + " AND a.owner_id=? ORDER BY a.created_at,a.id", actor.id())
        : s.rows(sql + " ORDER BY a.created_at,a.id LIMIT 200");
  }

  public Map<String, Object> account(Actor actor, UUID id) {
    var a =
        s.one(
            "SELECT a.*,b.balance FROM ledger_accounts a JOIN account_balances b ON"
                + " b.account_id=a.id WHERE a.id=? AND a.account_type='DEPOSIT'",
            id);
    if (actor.customer() && !actor.id().toString().equals(a.get("owner_id")))
      throw ApiException.missing();
    return a;
  }

  @Transactional
  public Map<String, Object> createAccount(Actor actor, String name) {
    actor.require("CUSTOMER");
    name = Support.text(name, 80, "Account name");
    s.db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, actor.id());
    if (s.db.queryForObject(
            "SELECT count(*) FROM ledger_accounts WHERE owner_id=?", Integer.class, actor.id())
        >= 5) throw new ApiException(409, "ACCOUNT_LIMIT", "Up to five demo accounts are allowed.");
    UUID id = createAccountFor(actor.id(), name, null);
    s.audit(actor.id(), "ACCOUNT_OPENED", id, "SUCCESS", "INR demo account opened");
    return account(actor, id);
  }

  public UUID createAccountFor(UUID owner, String name, String reference) {
    UUID id = UUID.randomUUID();
    s.db.update(
        "INSERT INTO ledger_accounts(id,owner_id,public_ref,name,account_type,normal_side)"
            + " VALUES(?,?,?,?, 'DEPOSIT','CREDIT')",
        id,
        owner,
        reference == null
            ? "DEMO-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT)
            : reference,
        name);
    s.db.update("INSERT INTO account_balances(account_id) VALUES(?)", id);
    return id;
  }

  private void lockAccounts(UUID... ids) {
    s.db.execute("SET LOCAL lock_timeout='3s'");
    Arrays.stream(ids)
        .distinct()
        .sorted()
        .forEach(
            id ->
                s.db.queryForObject(
                    "SELECT id FROM ledger_accounts WHERE id=? FOR UPDATE", UUID.class, id));
  }

  private BigDecimal balance(UUID id) {
    return s.db.queryForObject(
        "SELECT balance FROM account_balances WHERE account_id=?", BigDecimal.class, id);
  }

  private boolean active(UUID id) {
    return "ACTIVE"
        .equals(
            s.db.queryForObject("SELECT status FROM ledger_accounts WHERE id=?", String.class, id));
  }

  @Transactional
  public Result transfer(Actor actor, TransferInput input, String key) {
    actor.require("CUSTOMER");
    key = Support.text(key, 100, "Idempotency key");
    UUID source = Support.uuid(input.sourceAccountId());
    account(actor, source);
    String destination = Support.text(input.destinationAccountRef(), 45, "Destination account");
    BigDecimal amount = Support.money(input.amount());
    if (!"INR".equals(input.currency()))
      throw new ApiException(400, "INVALID_CURRENCY", "Only INR is supported.");
    String description =
        input.description() == null ? "Internal transfer" : input.description().strip();
    if (description.length() > 160)
      throw new ApiException(
          400, "INVALID_DESCRIPTION", "Description must be at most 160 characters.");
    String hash =
        Support.hash(
            s.json(
                List.of(
                    source.toString(), destination, amount.toPlainString(), "INR", description)));
    s.db.execute("SET LOCAL lock_timeout='3s'");
    int reserved =
        s.db.update(
            "INSERT INTO idempotency_requests(user_id,operation,request_key,request_hash)"
                + " VALUES(?,'TRANSFER',?,?) ON CONFLICT DO NOTHING",
            actor.id(),
            key,
            hash);
    if (reserved == 0) {
      var previous =
          s.one(
              "SELECT * FROM idempotency_requests WHERE user_id=? AND operation='TRANSFER' AND"
                  + " request_key=?",
              actor.id(),
              key);
      if (!hash.equals(previous.get("request_hash")))
        throw new ApiException(
            409, "IDEMPOTENCY_KEY_REUSED", "This key was already used for a different transfer.");
      if (previous.get("http_status") == null)
        throw new ApiException(
            503, "TRANSFER_RETRYABLE", "Transfer is still being processed. Retry the same key.");
      return new Result(
          ((Number) previous.get("http_status")).intValue(),
          s.parse(previous.get("response_body").toString()));
    }
    var destinations =
        s.rows(
            "SELECT id FROM ledger_accounts WHERE public_ref=? AND account_type='DEPOSIT'",
            destination);
    if (destinations.isEmpty())
      return reject(actor, key, "DESTINATION_NOT_FOUND", "Destination account was not found.");
    UUID target = Support.uuid(destinations.getFirst().get("id"));
    if (source.equals(target))
      return reject(actor, key, "SAME_ACCOUNT", "Choose a different destination account.");
    lockAccounts(source, target);
    if (!active(source) || !active(target))
      return reject(actor, key, "ACCOUNT_NOT_ACTIVE", "Both accounts must be active.");
    if (balance(source).compareTo(amount) < 0)
      return reject(actor, key, "INSUFFICIENT_FUNDS", "The source account has insufficient funds.");
    UUID id = UUID.randomUUID();
    String reference = "TX-" + id;
    UUID journal =
        post(
            actor.id(),
            "TRANSFER",
            reference,
            description,
            null,
            source,
            "DEBIT",
            target,
            "CREDIT",
            amount);
    s.db.update(
        "INSERT INTO transfers(id,actor_id,source_id,destination_id,amount,journal_id)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        actor.id(),
        source,
        target,
        amount,
        journal);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", id.toString());
    body.put("reference", reference);
    body.put("journal_id", journal.toString());
    body.put("status", "POSTED");
    body.put("amount", amount.toPlainString());
    body.put("currency", "INR");
    s.db.update(
        "UPDATE idempotency_requests SET http_status=201,response_body=?,transfer_id=? WHERE"
            + " user_id=? AND operation='TRANSFER' AND request_key=?",
        s.json(body),
        id,
        actor.id(),
        key);
    return new Result(201, body);
  }

  private Result reject(Actor actor, String key, String code, String message) {
    var body = Map.<String, Object>of("status", "REJECTED", "code", code, "message", message);
    s.db.update(
        "UPDATE idempotency_requests SET http_status=422,response_body=? WHERE user_id=? AND"
            + " operation='TRANSFER' AND request_key=?",
        s.json(body),
        actor.id(),
        key);
    s.audit(actor.id(), "TRANSFER_REJECTED", key, "REJECTED", code);
    return new Result(422, body);
  }

  // Callers hold both account locks. Journal, entries, projections, audit and outbox share one
  // transaction.
  private UUID post(
      UUID actor,
      String type,
      String reference,
      String description,
      UUID original,
      UUID first,
      String firstSide,
      UUID second,
      String secondSide,
      BigDecimal amount) {
    UUID journal = UUID.randomUUID();
    s.db.update(
        "INSERT INTO journals(id,reference,event_type,currency,reversal_of,description)"
            + " VALUES(?,?,?,'INR',?,?)",
        journal,
        reference,
        type,
        original,
        description);
    s.db.update(
        "INSERT INTO ledger_entries(id,journal_id,account_id,side,amount) VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        journal,
        first,
        firstSide,
        amount);
    s.db.update(
        "INSERT INTO ledger_entries(id,journal_id,account_id,side,amount) VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        journal,
        second,
        secondSide,
        amount);
    changeBalance(first, firstSide, amount);
    probe.afterFirstBalanceWrite();
    changeBalance(second, secondSide, amount);
    s.audit(actor, type + "_POSTED", journal, "SUCCESS", description);
    s.db.update(
        "INSERT INTO outbox_events(id,actor_id,aggregate_id,event_type,payload) VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        actor,
        journal,
        type + "_POSTED",
        s.json(
            Map.of("reference", reference, "amount", amount.toPlainString(), "currency", "INR")));
    return journal;
  }

  private void changeBalance(UUID account, String side, BigDecimal amount) {
    String normal =
        s.db.queryForObject(
            "SELECT normal_side FROM ledger_accounts WHERE id=?", String.class, account);
    s.db.update(
        "UPDATE account_balances SET balance=balance+?,updated_at=clock_timestamp() WHERE"
            + " account_id=?",
        normal.equals(side) ? amount : amount.negate(),
        account);
  }

  @Transactional
  public UUID fundDemo(UUID actor, UUID target, BigDecimal amount, String reference) {
    lockAccounts(CASH, target);
    return post(
        actor,
        "FUNDING",
        reference,
        "Synthetic opening funds",
        null,
        CASH,
        "DEBIT",
        target,
        "CREDIT",
        amount);
  }

  @Transactional
  public void freeze(Actor actor, UUID id, boolean frozen, String reason) {
    actor.require("OPERATOR");
    account(actor, id);
    reason = Support.text(reason, 300, "Reason");
    lockAccounts(id);
    s.db.update("UPDATE ledger_accounts SET status=? WHERE id=?", frozen ? "FROZEN" : "ACTIVE", id);
    s.audit(actor.id(), frozen ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN", id, "SUCCESS", reason);
  }

  public Map<String, Object> transferDetail(Actor actor, UUID id) {
    var t =
        s.one(
            "SELECT t.*,j.reference,j.description,j.posted_at,sa.public_ref"
                + " source_ref,da.public_ref destination_ref,EXISTS(SELECT 1 FROM journals r WHERE"
                + " r.reversal_of=j.id) reversed FROM transfers t JOIN journals j ON"
                + " j.id=t.journal_id JOIN ledger_accounts sa ON sa.id=t.source_id JOIN"
                + " ledger_accounts da ON da.id=t.destination_id WHERE t.id=?",
            id);
    if (actor.customer()) {
      UUID source = Support.uuid(t.get("source_id")), dest = Support.uuid(t.get("destination_id"));
      if (s.db.queryForObject(
              "SELECT count(*) FROM ledger_accounts WHERE id IN (?,?) AND owner_id=?",
              Integer.class,
              source,
              dest,
              actor.id())
          == 0) throw ApiException.missing();
    }
    return t;
  }

  public Map<String, Object> requestStatus(Actor actor, String key) {
    var r =
        s.one(
            "SELECT http_status,response_body FROM idempotency_requests WHERE user_id=? AND"
                + " operation='TRANSFER' AND request_key=?",
            actor.id(),
            key);
    return Map.of(
        "http_status", r.get("http_status"), "result", s.parse(r.get("response_body").toString()));
  }

  public List<Map<String, Object>> history(
      Actor actor, UUID id, int page, int size, java.time.Instant start, java.time.Instant end) {
    account(actor, id);
    validateRange(start, end);
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new ApiException(400, "INVALID_PAGE", "Use page >= 0 and a page size from 1 to 100.");
    return s.rows(
        "SELECT e.id,j.id journal_id,j.reference,j.event_type,j.description,j.posted_at,e.side,CASE"
            + " e.side WHEN 'CREDIT' THEN e.amount ELSE -e.amount END signed_amount,j.currency,t.id"
            + " transfer_id,j.reversal_of FROM ledger_entries e JOIN journals j ON"
            + " j.id=e.journal_id LEFT JOIN transfers t ON t.journal_id=j.id WHERE e.account_id=?"
            + " AND j.posted_at>=? AND j.posted_at<? ORDER BY j.posted_at DESC,e.id DESC LIMIT ?"
            + " OFFSET ?",
        id,
        java.sql.Timestamp.from(start),
        java.sql.Timestamp.from(end),
        size,
        page * size);
  }

  public static void validateRange(java.time.Instant start, java.time.Instant end) {
    if (!end.isAfter(start) || java.time.Duration.between(start, end).toDays() > 3660)
      throw new ApiException(
          400, "INVALID_INTERVAL", "Choose an increasing interval of at most ten years.");
  }

  @Transactional
  public UUID requestCorrection(Actor actor, UUID journal, UUID exception, String reason) {
    actor.require("OPERATOR");
    reason = Support.text(reason, 300, "Reason");
    var j = s.one("SELECT event_type FROM journals WHERE id=?", journal);
    if (!"TRANSFER".equals(j.get("event_type")))
      throw new ApiException(
          409, "INVALID_CORRECTION", "Only a posted internal transfer can be reversed.");
    if (s.db.queryForObject(
            "SELECT count(*) FROM journals WHERE reversal_of=?", Integer.class, journal)
        > 0) throw new ApiException(409, "ALREADY_REVERSED", "This transfer is already reversed.");
    if (exception != null) {
      var e = s.one("SELECT * FROM reconciliation_exceptions WHERE id=? FOR UPDATE", exception);
      if (Set.of("RESOLVED", "DISMISSED").contains(e.get("state")))
        throw new ApiException(409, "EXCEPTION_CLOSED", "This exception is closed.");
      // A correction can only resolve an exception whose evidence points to this journal.
      Integer links =
          s.db.queryForObject(
              "SELECT count(*) FROM reconciliation_exceptions x LEFT JOIN"
                  + " reconciliation_snapshot_items si ON si.id=x.snapshot_item_id LEFT JOIN"
                  + " ledger_entries le ON le.id=si.entry_id LEFT JOIN statement_rows sr ON"
                  + " sr.id=x.statement_row_id JOIN journals j ON j.id=? WHERE x.id=? AND"
                  + " (le.journal_id=j.id OR sr.external_reference=j.reference)",
              Integer.class,
              journal,
              exception);
      if (links == null || links == 0)
        throw new ApiException(
            409, "UNRELATED_EVIDENCE", "The exception does not reference this journal.");
      s.db.update(
          "UPDATE reconciliation_exceptions SET state='AWAITING_APPROVAL' WHERE id=?", exception);
      s.db.update(
          "INSERT INTO exception_events(id,exception_id,actor_id,state,note)"
              + " VALUES(?,?,?,'AWAITING_APPROVAL',?)",
          UUID.randomUUID(),
          exception,
          actor.id(),
          reason);
    }
    UUID id = UUID.randomUUID();
    s.db.update(
        "INSERT INTO correction_requests(id,journal_id,exception_id,requester_id,reason)"
            + " VALUES(?,?,?,?,?)",
        id,
        journal,
        exception,
        actor.id(),
        reason);
    s.audit(actor.id(), "CORRECTION_REQUESTED", id, "SUCCESS", reason);
    return id;
  }

  @Transactional
  public Map<String, Object> decideCorrection(Actor actor, UUID id, boolean approve, String note) {
    actor.require("APPROVER");
    note = Support.text(note, 300, "Decision reason");
    var c = s.one("SELECT * FROM correction_requests WHERE id=? FOR UPDATE", id);
    if (actor.id().toString().equals(c.get("requester_id")))
      throw new ApiException(
          403, "SELF_APPROVAL", "A different staff member must decide this request.");
    if (!"PENDING".equals(c.get("state")))
      throw new ApiException(409, "ALREADY_DECIDED", "This request already has a decision.");
    UUID original = Support.uuid(c.get("journal_id")), reversal = null;
    if (approve) {
      s.db.queryForObject(
          "SELECT id FROM transfers WHERE journal_id=? FOR UPDATE", UUID.class, original);
      if (s.db.queryForObject(
              "SELECT count(*) FROM journals WHERE reversal_of=?", Integer.class, original)
          > 0)
        throw new ApiException(409, "ALREADY_REVERSED", "This transfer is already reversed.");
      var transfer = s.one("SELECT * FROM transfers WHERE journal_id=?", original);
      UUID source = Support.uuid(transfer.get("source_id")),
          dest = Support.uuid(transfer.get("destination_id"));
      BigDecimal amount = new BigDecimal(transfer.get("amount").toString());
      lockAccounts(source, dest);
      if (!active(source) || !active(dest))
        throw new ApiException(
            409, "ACCOUNT_NOT_ACTIVE", "Both accounts must be active before reversal.");
      if (balance(dest).compareTo(amount) < 0)
        throw new ApiException(
            422,
            "REVERSAL_INSUFFICIENT_FUNDS",
            "Recipient has spent these funds. Request remains pending for review.");
      String description = "Full reversal: " + c.get("reason");
      reversal =
          post(
              actor.id(),
              "REVERSAL",
              "RV-" + UUID.randomUUID(),
              description.substring(0, Math.min(160, description.length())),
              original,
              dest,
              "DEBIT",
              source,
              "CREDIT",
              amount);
    }
    s.db.update(
        "UPDATE correction_requests SET state=?,approver_id=?,decision_reason=?,reversal_id=? WHERE"
            + " id=?",
        approve ? "APPROVED" : "REJECTED",
        actor.id(),
        note,
        reversal,
        id);
    if (c.get("exception_id") != null) {
      UUID exception = Support.uuid(c.get("exception_id"));
      String state = approve ? "RESOLVED" : "INVESTIGATING";
      s.db.update("UPDATE reconciliation_exceptions SET state=? WHERE id=?", state, exception);
      s.db.update(
          "INSERT INTO exception_events(id,exception_id,actor_id,state,note) VALUES(?,?,?,?,?)",
          UUID.randomUUID(),
          exception,
          actor.id(),
          state,
          note);
    }
    s.audit(
        actor.id(), approve ? "CORRECTION_APPROVED" : "CORRECTION_REJECTED", id, "SUCCESS", note);
    return s.one("SELECT * FROM correction_requests WHERE id=?", id);
  }
}
