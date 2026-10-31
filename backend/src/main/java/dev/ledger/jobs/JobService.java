package dev.ledger.jobs;

import dev.ledger.identity.*;
import dev.ledger.ledger.LedgerService;
import dev.ledger.shared.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {
  private final Support s;
  private final LedgerService ledger;

  public JobService(Support s, LedgerService ledger) {
    this.s = s;
    this.ledger = ledger;
  }

  @Transactional
  public Map<String, Object> schedule(Actor actor, Map<String, String> input, String key) {
    actor.require("CUSTOMER");
    key = Support.text(key, 100, "Idempotency key");
    UUID source = Support.uuid(input.get("sourceAccountId"));
    ledger.account(actor, source);
    String dest = Support.text(input.get("destinationAccountRef"), 45, "Destination");
    String amount = Support.money(input.get("amount")).toPlainString();
    String description = Support.text(input.get("description"), 160, "Description");
    Instant due = Support.instant(input.get("dueAt"));
    String hash =
        Support.hash(s.json(List.of(source.toString(), dest, amount, description, due.toString())));
    var existing =
        s.rows(
            "SELECT * FROM scheduled_transfers WHERE actor_id=? AND creation_key=?",
            actor.id(),
            key);
    if (!existing.isEmpty()) return replay(existing.getFirst(), hash);
    if (due.isBefore(Instant.now().plusSeconds(5))
        || due.isAfter(Instant.now().plus(Duration.ofDays(365))))
      throw new ApiException(
          400, "INVALID_DUE_TIME", "Schedule between five seconds and one year in the future.");
    s.one(
        "SELECT id FROM ledger_accounts WHERE public_ref=? AND account_type='DEPOSIT' AND id<>?",
        dest,
        source);
    UUID id = UUID.randomUUID();
    int inserted =
        s.db.update(
            "INSERT INTO"
                + " scheduled_transfers(id,actor_id,source_id,destination_ref,amount,description,due_at,next_attempt_at,creation_key,request_hash)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(actor_id,creation_key) DO NOTHING",
            id,
            actor.id(),
            source,
            dest,
            new java.math.BigDecimal(amount),
            description,
            Timestamp.from(due),
            Timestamp.from(due),
            key,
            hash);
    if (inserted == 0)
      return replay(
          s.one(
              "SELECT * FROM scheduled_transfers WHERE actor_id=? AND creation_key=?",
              actor.id(),
              key),
          hash);
    s.audit(
        actor.id(),
        "TRANSFER_SCHEDULED",
        id,
        "SUCCESS",
        "Funds are checked at execution, not reserved.");
    return s.one("SELECT * FROM scheduled_transfers WHERE id=?", id);
  }

  private Map<String, Object> replay(Map<String, Object> previous, String hash) {
    if (!hash.equals(previous.get("request_hash")))
      throw new ApiException(
          409, "IDEMPOTENCY_KEY_REUSED", "This key was used for a different schedule.");
    return previous;
  }

  public List<Map<String, Object>> list(Actor actor) {
    return actor.customer()
        ? s.rows(
            "SELECT * FROM scheduled_transfers WHERE actor_id=? ORDER BY created_at DESC LIMIT 100",
            actor.id())
        : s.rows("SELECT * FROM scheduled_transfers ORDER BY created_at DESC LIMIT 100");
  }

  @Transactional
  public void cancel(Actor actor, UUID id) {
    actor.require("CUSTOMER");
    var job =
        s.one(
            "SELECT * FROM scheduled_transfers WHERE id=? AND actor_id=? FOR UPDATE",
            id,
            actor.id());
    if (!job.get("state").equals("PENDING"))
      throw new ApiException(409, "ALREADY_PROCESSED", "Only pending schedules can be cancelled.");
    s.db.update("UPDATE scheduled_transfers SET state='CANCELLED' WHERE id=?", id);
    s.audit(actor.id(), "SCHEDULE_CANCELLED", id, "SUCCESS", "Customer cancelled before execution");
  }
}
