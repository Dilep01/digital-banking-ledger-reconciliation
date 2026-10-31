package dev.ledger.web;

import dev.ledger.identity.*;
import dev.ledger.jobs.JobService;
import dev.ledger.ledger.LedgerService;
import dev.ledger.reconciliation.ReconciliationService;
import dev.ledger.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final IdentityService identity;
  private final LedgerService ledger;
  private final ReconciliationService recon;
  private final JobService jobs;
  private final Support s;

  public ApiController(
      IdentityService identity,
      LedgerService ledger,
      ReconciliationService recon,
      JobService jobs,
      Support s) {
    this.identity = identity;
    this.ledger = ledger;
    this.recon = recon;
    this.jobs = jobs;
    this.s = s;
  }

  @GetMapping("/auth/csrf")
  Map<String, String> csrf(HttpServletRequest request) {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/auth/register")
  ResponseEntity<?> register(@RequestBody Map<String, String> body) {
    return ResponseEntity.status(201).body(identity.register(body));
  }

  @GetMapping("/auth/me")
  Actor me() {
    return identity.current();
  }

  @GetMapping("/accounts")
  Object accounts() {
    return ledger.accounts(identity.current());
  }

  @PostMapping("/accounts")
  ResponseEntity<?> createAccount(@RequestBody Map<String, String> body) {
    return ResponseEntity.status(201)
        .body(ledger.createAccount(identity.current(), body.get("name")));
  }

  @GetMapping({"/accounts/{id}", "/accounts/{id}/balance"})
  Object account(@PathVariable UUID id) {
    return ledger.account(identity.current(), id);
  }

  @PostMapping("/accounts/{id}/freeze")
  Object freeze(@PathVariable UUID id, @RequestBody Map<String, String> body) {
    ledger.freeze(identity.current(), id, true, body.get("reason"));
    return Map.of("status", "FROZEN");
  }

  @PostMapping("/accounts/{id}/unfreeze")
  Object unfreeze(@PathVariable UUID id, @RequestBody Map<String, String> body) {
    ledger.freeze(identity.current(), id, false, body.get("reason"));
    return Map.of("status", "ACTIVE");
  }

  @PostMapping("/transfers")
  ResponseEntity<?> transfer(
      @RequestBody LedgerService.TransferInput body, @RequestHeader("Idempotency-Key") String key) {
    var result = ledger.transfer(identity.current(), body, key);
    return ResponseEntity.status(result.status()).body(result.body());
  }

  @GetMapping("/transfers/{id}")
  Object transfer(@PathVariable UUID id) {
    return ledger.transferDetail(identity.current(), id);
  }

  @GetMapping("/transfer-requests/{key}")
  Object requestStatus(@PathVariable String key) {
    return ledger.requestStatus(identity.current(), key);
  }

  @GetMapping("/accounts/{id}/transactions")
  Object history(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String start,
      @RequestParam(required = false) String end) {
    return ledger.history(
        identity.current(),
        id,
        page,
        size,
        start == null ? Instant.now().minus(Duration.ofDays(365)) : Support.instant(start),
        end == null ? Instant.now().plusSeconds(1) : Support.instant(end));
  }

  @GetMapping(value = "/accounts/{id}/statement.csv", produces = "text/csv")
  ResponseEntity<String> statement(
      @PathVariable UUID id,
      @RequestParam(required = false) String start,
      @RequestParam(required = false) String end) {
    var actor = identity.current();
    ledger.account(actor, id);
    Instant
        from = start == null ? Instant.now().minus(Duration.ofDays(365)) : Support.instant(start),
        until = end == null ? Instant.now().plusSeconds(1) : Support.instant(end);
    LedgerService.validateRange(from, until);
    var rows =
        s.rows(
            "SELECT j.reference,j.posted_at,j.event_type,j.description,CASE e.side WHEN 'CREDIT'"
                + " THEN e.amount ELSE -e.amount END amount,j.currency FROM ledger_entries e JOIN"
                + " journals j ON j.id=e.journal_id WHERE e.account_id=? AND j.posted_at>=? AND"
                + " j.posted_at<? ORDER BY j.posted_at,e.id LIMIT 5001",
            id,
            java.sql.Timestamp.from(from),
            java.sql.Timestamp.from(until));
    if (rows.size() > 5000)
      throw new ApiException(
          400, "INTERVAL_TOO_LARGE", "Narrow the interval to at most 5,000 entries.");
    StringBuilder out =
        new StringBuilder(
            "reference,booking_time,event_type,description,signed_amount,currency\r\n");
    for (var row : rows)
      out.append(csv(row.get("reference")))
          .append(',')
          .append(csv(row.get("posted_at")))
          .append(',')
          .append(csv(row.get("event_type")))
          .append(',')
          .append(csv(row.get("description")))
          .append(',')
          .append(row.get("amount"))
          .append(',')
          .append(row.get("currency"))
          .append("\r\n");
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=statement.csv")
        .body(out.toString());
  }

  private static String csv(Object value) {
    String text = String.valueOf(value);
    String stripped = text.stripLeading();
    if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) text = "'" + text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  @GetMapping("/overview")
  Object overview() {
    var actor = identity.current();
    var accounts = ledger.accounts(actor);
    Map<String, Object> overview = new LinkedHashMap<>();
    overview.put("accounts", accounts);
    if (actor.customer()) {
      overview.put(
          "transfer_count",
          s.db.queryForObject(
              "SELECT count(*) FROM transfers WHERE actor_id=?", Integer.class, actor.id()));
      overview.put(
          "recent",
          s.rows(
              "SELECT"
                  + " j.reference,j.event_type,j.posted_at,j.description,e.side,e.amount,a.public_ref"
                  + " FROM ledger_entries e JOIN journals j ON j.id=e.journal_id JOIN"
                  + " ledger_accounts a ON a.id=e.account_id WHERE a.owner_id=? ORDER BY"
                  + " j.posted_at DESC,e.id DESC LIMIT 8",
              actor.id()));
    } else {
      overview.put(
          "transfer_count", s.db.queryForObject("SELECT count(*) FROM transfers", Integer.class));
      overview.put(
          "open_exceptions",
          s.db.queryForObject(
              "SELECT count(*) FROM reconciliation_exceptions WHERE state NOT IN"
                  + " ('RESOLVED','DISMISSED')",
              Integer.class));
      overview.put(
          "pending_approvals",
          s.db.queryForObject(
              "SELECT count(*) FROM correction_requests WHERE state='PENDING'", Integer.class));
      overview.put(
          "recent",
          s.rows(
              "SELECT action,target,outcome,reason,occurred_at FROM audit_events ORDER BY"
                  + " occurred_at DESC,id DESC LIMIT 8"));
    }
    overview.put(
        "notifications",
        s.rows(
            "SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 5",
            actor.id()));
    return overview;
  }

  @PostMapping("/scheduled-transfers")
  ResponseEntity<?> schedule(
      @RequestBody Map<String, String> body, @RequestHeader("Idempotency-Key") String key) {
    return ResponseEntity.status(201).body(jobs.schedule(identity.current(), body, key));
  }

  @GetMapping("/scheduled-transfers")
  Object schedules() {
    return jobs.list(identity.current());
  }

  @PostMapping("/scheduled-transfers/{id}/cancel")
  Object cancel(@PathVariable UUID id) {
    jobs.cancel(identity.current(), id);
    return Map.of("status", "CANCELLED");
  }

  @PostMapping(value = "/ops/statement-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Object upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "SYNTHETIC") String source)
      throws IOException {
    return recon.importStatement(
        identity.current(),
        source,
        file.getOriginalFilename() == null ? "statement.csv" : file.getOriginalFilename(),
        file.getBytes());
  }

  @GetMapping("/ops/statement-imports")
  Object imports() {
    identity.current().require("OPERATOR", "APPROVER", "AUDITOR");
    return s.rows(
        "SELECT i.id,i.source,i.original_name,i.file_hash,i.created_at,count(r.id)"
            + " row_count,count(r.validation_error) invalid_rows FROM statement_imports i LEFT JOIN"
            + " statement_rows r ON r.import_id=i.id GROUP BY i.id ORDER BY i.created_at DESC LIMIT"
            + " 100");
  }

  @GetMapping("/ops/statement-imports/{id}")
  Object importDetail(@PathVariable UUID id) {
    identity.current().require("OPERATOR", "APPROVER", "AUDITOR");
    var result =
        new LinkedHashMap<>(
            s.one(
                "SELECT id,source,file_hash,original_name,parser_version,created_at FROM"
                    + " statement_imports WHERE id=?",
                id));
    result.put(
        "rows", s.rows("SELECT * FROM statement_rows WHERE import_id=? ORDER BY line_number", id));
    return result;
  }

  @PostMapping("/ops/reconciliation-runs")
  Object run(@RequestBody Map<String, String> body) {
    return recon.run(
        identity.current(),
        body.get("importId") == null || body.get("importId").isBlank()
            ? null
            : Support.uuid(body.get("importId")),
        Support.instant(body.get("start")),
        Support.instant(body.get("end")));
  }

  @GetMapping("/ops/reconciliation-runs")
  Object runs() {
    identity.current().require("OPERATOR", "APPROVER", "AUDITOR");
    return s.rows("SELECT * FROM reconciliation_runs ORDER BY created_at DESC LIMIT 100");
  }

  @GetMapping("/ops/reconciliation-runs/{id}")
  Object run(@PathVariable UUID id) {
    return recon.detail(identity.current(), id);
  }

  @GetMapping("/ops/reconciliation-exceptions")
  Object exceptions(@RequestParam(required = false) UUID runId) {
    identity.current().require("OPERATOR", "APPROVER", "AUDITOR");
    return runId == null
        ? s.rows("SELECT * FROM reconciliation_exceptions ORDER BY created_at DESC LIMIT 500")
        : s.rows(
            "SELECT * FROM reconciliation_exceptions WHERE run_id=? ORDER BY created_at DESC LIMIT"
                + " 2000",
            runId);
  }

  @GetMapping("/ops/reconciliation-exceptions/{id}")
  Object exception(@PathVariable UUID id) {
    return recon.exceptionDetail(identity.current(), id);
  }

  @PostMapping("/ops/reconciliation-exceptions/{id}/events")
  Object event(@PathVariable UUID id, @RequestBody Map<String, String> body) {
    recon.event(identity.current(), id, body.get("state"), body.get("note"));
    return Map.of("saved", true);
  }

  @PostMapping("/ops/correction-requests")
  Object requestCorrection(@RequestBody Map<String, String> body) {
    UUID id =
        ledger.requestCorrection(
            identity.current(),
            Support.uuid(body.get("journalId")),
            body.get("exceptionId") == null || body.get("exceptionId").isBlank()
                ? null
                : Support.uuid(body.get("exceptionId")),
            body.get("reason"));
    return Map.of("id", id);
  }

  @GetMapping("/ops/correction-requests")
  Object corrections() {
    identity.current().require("OPERATOR", "APPROVER", "AUDITOR");
    return s.rows(
        "SELECT c.*,j.reference,u.display_name requester_name FROM correction_requests c JOIN"
            + " journals j ON j.id=c.journal_id JOIN users u ON u.id=c.requester_id ORDER BY"
            + " c.created_at DESC LIMIT 200");
  }

  @PostMapping("/ops/correction-requests/{id}/approve")
  Object approve(@PathVariable UUID id, @RequestBody Map<String, String> body) {
    return ledger.decideCorrection(identity.current(), id, true, body.get("reason"));
  }

  @PostMapping("/ops/correction-requests/{id}/reject")
  Object reject(@PathVariable UUID id, @RequestBody Map<String, String> body) {
    return ledger.decideCorrection(identity.current(), id, false, body.get("reason"));
  }

  @GetMapping("/audit/events")
  Object audit(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "") String search) {
    identity.current().require("OPERATOR", "APPROVER", "AUDITOR");
    if (page < 0 || page > 10000 || search.length() > 100)
      throw new ApiException(400, "INVALID_QUERY", "Invalid audit query.");
    return s.rows(
        "SELECT a.*,u.display_name actor_name FROM audit_events a LEFT JOIN users u ON"
            + " u.id=a.actor_id WHERE a.action ILIKE ? OR a.target ILIKE ? ORDER BY a.occurred_at"
            + " DESC,a.id DESC LIMIT 50 OFFSET ?",
        "%" + search + "%",
        "%" + search + "%",
        page * 50);
  }

  @GetMapping("/audit/journals")
  Object journals() {
    identity.current().require("OPERATOR", "APPROVER", "AUDITOR");
    return s.rows("SELECT * FROM journals ORDER BY posted_at DESC LIMIT 200");
  }

  @GetMapping("/audit/journals/{id}")
  Object journal(@PathVariable UUID id) {
    identity.current().require("OPERATOR", "APPROVER", "AUDITOR");
    var result = new LinkedHashMap<>(s.one("SELECT * FROM journals WHERE id=?", id));
    result.put(
        "entries",
        s.rows(
            "SELECT e.*,a.public_ref,a.name FROM ledger_entries e JOIN ledger_accounts a ON"
                + " a.id=e.account_id WHERE journal_id=? ORDER BY e.side,a.public_ref",
            id));
    return result;
  }

  @GetMapping("/ops/jobs")
  Object jobStatus() {
    identity.current().require("OPERATOR", "APPROVER", "AUDITOR");
    return Map.of(
        "outbox",
        s.rows("SELECT * FROM outbox_events ORDER BY created_at DESC LIMIT 100"),
        "schedules",
        jobs.list(identity.current()));
  }

  @PostMapping("/ops/outbox/{id}/retry")
  Object retry(@PathVariable UUID id) {
    var actor = identity.current();
    actor.require("OPERATOR");
    int changed =
        s.db.update(
            "UPDATE outbox_events SET state='PENDING',attempts=0,next_attempt_at=now() WHERE id=?"
                + " AND state='FAILED'",
            id);
    if (changed == 0)
      throw new ApiException(409, "NOT_FAILED", "Only failed events can be requeued.");
    s.audit(actor.id(), "EVENT_REQUEUED", id, "SUCCESS", "Operator requested safe replay");
    return Map.of("requeued", true);
  }
}
