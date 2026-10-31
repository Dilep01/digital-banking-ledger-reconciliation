package dev.ledger.jobs;

import dev.ledger.identity.IdentityService;
import dev.ledger.ledger.LedgerService;
import dev.ledger.shared.Support;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class JobWorker {
  private final Support s;
  private final LedgerService ledger;
  private final IdentityService identity;
  private final DeliveryProbe probe;
  private final TransactionTemplate tx;
  private final boolean enabled;

  public JobWorker(
      Support s,
      LedgerService ledger,
      IdentityService identity,
      DeliveryProbe probe,
      PlatformTransactionManager manager,
      @Value("${app.worker-enabled}") boolean enabled) {
    this.s = s;
    this.ledger = ledger;
    this.identity = identity;
    this.probe = probe;
    this.tx = new TransactionTemplate(manager);
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${app.worker-delay}")
  public void tick() {
    if (!enabled) return;
    for (int i = 0; i < 10; i++) {
      boolean schedule = processSchedule();
      boolean event = processEvent();
      if (!schedule && !event) break;
    }
    tx.executeWithoutResult(
        status -> s.db.update("DELETE FROM login_buckets WHERE expires_at<now()-interval '1 day'"));
  }

  public boolean processEvent() {
    UUID[] current = {null};
    try {
      return Boolean.TRUE.equals(
          tx.execute(
              status -> {
                var events =
                    s.rows(
                        "SELECT * FROM outbox_events WHERE state='PENDING' AND"
                            + " next_attempt_at<=now() ORDER BY created_at,id LIMIT 1 FOR UPDATE"
                            + " SKIP LOCKED");
                if (events.isEmpty()) return false;
                var event = events.getFirst();
                UUID id = Support.uuid(event.get("id"));
                current[0] = id;
                int fresh =
                    s.db.update(
                        "INSERT INTO processed_events(consumer_id,event_id)"
                            + " VALUES('notification-inbox-v1',?) ON CONFLICT DO NOTHING",
                        id);
                if (fresh == 1) {
                  var payload = s.parse(event.get("payload").toString());
                  s.db.update(
                      "INSERT INTO notifications(id,user_id,event_id,message) VALUES(?,?,?,?) ON"
                          + " CONFLICT(event_id) DO NOTHING",
                      UUID.randomUUID(),
                      Support.uuid(event.get("actor_id")),
                      id,
                      event.get("event_type")
                          + ": INR "
                          + payload.get("amount")
                          + " · "
                          + payload.get("reference"));
                  probe.afterConsumerWrite();
                }
                s.db.update(
                    "UPDATE outbox_events SET state='DELIVERED',attempts=attempts+1,last_error=NULL"
                        + " WHERE id=?",
                    id);
                return true;
              }));
    } catch (Exception ex) {
      if (current[0] != null) fail("outbox_events", current[0], ex);
      return false;
    }
  }

  public boolean processSchedule() {
    UUID[] current = {null};
    try {
      return Boolean.TRUE.equals(
          tx.execute(
              status -> {
                var jobs =
                    s.rows(
                        "SELECT * FROM scheduled_transfers WHERE state='PENDING' AND due_at<=now()"
                            + " AND next_attempt_at<=now() ORDER BY due_at,id LIMIT 1 FOR UPDATE"
                            + " SKIP LOCKED");
                if (jobs.isEmpty()) return false;
                var job = jobs.getFirst();
                UUID id = Support.uuid(job.get("id"));
                current[0] = id;
                var actor = identity.byId(Support.uuid(job.get("actor_id")));
                var result =
                    ledger.transfer(
                        actor,
                        new LedgerService.TransferInput(
                            job.get("source_id").toString(),
                            job.get("destination_ref").toString(),
                            job.get("amount").toString(),
                            "INR",
                            job.get("description").toString()),
                        "scheduled/" + id);
                s.db.update(
                    "UPDATE scheduled_transfers SET state=?,attempts=attempts+1,result=? WHERE"
                        + " id=?",
                    result.status() == 201 ? "COMPLETED" : "REJECTED",
                    s.json(result.body()),
                    id);
                return true;
              }));
    } catch (Exception ex) {
      if (current[0] != null) fail("scheduled_transfers", current[0], ex);
      return false;
    }
  }

  private void fail(String table, UUID id, Exception ex) {
    // Table is a private constant selected by the worker, never request input.
    String message = "Processing failed; see correlated server logs";
    org.slf4j.LoggerFactory.getLogger(JobWorker.class)
        .warn("Background processing failed for {} {}", table, id, ex);
    tx.executeWithoutResult(
        status -> {
          if (table.equals("outbox_events"))
            s.db.update(
                "UPDATE outbox_events SET attempts=attempts+1,state=CASE WHEN attempts>=4 THEN"
                    + " 'FAILED' ELSE 'PENDING' END,next_attempt_at=now()+interval '10"
                    + " seconds',last_error=? WHERE id=? AND state='PENDING'",
                message,
                id);
          else
            s.db.update(
                "UPDATE scheduled_transfers SET attempts=attempts+1,state=CASE WHEN attempts>=4"
                    + " THEN 'FAILED' ELSE 'PENDING' END,next_attempt_at=now()+interval '10"
                    + " seconds',result=? WHERE id=? AND state='PENDING'",
                message,
                id);
        });
  }
}
