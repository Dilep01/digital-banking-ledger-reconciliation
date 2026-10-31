package dev.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.ledger.identity.*;
import dev.ledger.jobs.*;
import dev.ledger.ledger.*;
import dev.ledger.reconciliation.ReconciliationService;
import dev.ledger.shared.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BankingIntegrationTest {
  @Autowired Support s;
  @Autowired LedgerService ledger;
  @Autowired ReconciliationService recon;
  @Autowired IdentityService identities;
  @Autowired PasswordEncoder encoder;
  @Autowired PlatformTransactionManager manager;
  @Autowired JobWorker worker;
  @Autowired JobService jobs;
  @Autowired MockMvc mvc;
  @MockitoSpyBean PostingProbe postingProbe;
  @MockitoSpyBean DeliveryProbe deliveryProbe;
  JdbcTemplate owner;
  TransactionTemplate tx;
  Actor alice, bob, operator, approver, auditor;
  UUID a, b;

  @BeforeEach
  void prepare() {
    owner =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:55438/ledger_test",
                "ledger_owner",
                "local_owner_only"));
    owner.execute(
        "TRUNCATE"
            + " users,ledger_accounts,account_balances,journals,ledger_entries,transfers,idempotency_requests,audit_events,statement_imports,statement_rows,reconciliation_runs,reconciliation_snapshot_items,reconciliation_exceptions,exception_events,correction_requests,outbox_events,processed_events,notifications,scheduled_transfers,login_buckets,spring_session"
            + " CASCADE");
    tx = new TransactionTemplate(manager);
    tx.executeWithoutResult(
        status -> new DemoData(s, encoder, ledger, "TestPassword!2026").run(null));
    alice = identities.byEmail("alice@ledger.local");
    bob = identities.byEmail("bob@ledger.local");
    operator = identities.byEmail("operator@ledger.local");
    approver = identities.byEmail("approver@ledger.local");
    auditor = identities.byEmail("auditor@ledger.local");
    a = Support.uuid(ledger.accounts(alice).getFirst().get("id"));
    b = Support.uuid(ledger.accounts(bob).getFirst().get("id"));
    reset(postingProbe, deliveryProbe);
  }

  LedgerService.TransferInput input(String amount) {
    return new LedgerService.TransferInput(
        a.toString(), "DEMO-BOB", amount, "INR", "Test transfer");
  }

  LedgerService.Result transfer(String amount, String key) {
    return ledger.transfer(alice, input(amount), key);
  }

  BigDecimal balance(UUID id) {
    return s.db.queryForObject(
        "SELECT balance FROM account_balances WHERE account_id=?", BigDecimal.class, id);
  }

  int count(String table) {
    return s.db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  UUID journal(LedgerService.Result result) {
    return Support.uuid(result.body().get("journal_id"));
  }

  @Test
  void postsBalancedTransfer() {
    var result = transfer("500", "one");
    assertThat(result.status()).isEqualTo(201);
    assertThat(balance(a)).isEqualByComparingTo("4500");
    assertThat(balance(b)).isEqualByComparingTo("1500");
    assertThat(count("transfers")).isEqualTo(1);
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE journal_id=?",
                Integer.class,
                journal(result)))
        .isEqualTo(2);
    assertThat(
            recon
                .run(operator, null, Instant.now().minusSeconds(60), Instant.now().plusSeconds(1))
                .get("exception_count"))
        .isEqualTo(0);
  }

  @Test
  void replaysIdenticalIntent() {
    var first = transfer("500", "same");
    var second = transfer("500.00", "same");
    assertThat(second).isEqualTo(first);
    assertThat(count("transfers")).isEqualTo(1);
  }

  @Test
  void rejectsChangedKeyPayload() {
    transfer("500", "same");
    assertThatThrownBy(() -> transfer("501", "same"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("different transfer");
    assertThat(count("transfers")).isEqualTo(1);
  }

  @Test
  void rejectsInsufficientFundsAndReplaysRejection() {
    var first = transfer("6000", "no-funds");
    assertThat(first.status()).isEqualTo(422);
    assertThat(transfer("6000", "no-funds")).isEqualTo(first);
    assertThat(balance(a)).isEqualByComparingTo("5000");
    assertThat(count("transfers")).isZero();
  }

  @Test
  void checksOwnershipBeforePosting() {
    assertThatThrownBy(() -> ledger.transfer(bob, input("10"), "theft"))
        .isInstanceOf(ApiException.class);
    assertThat(count("transfers")).isZero();
  }

  @Test
  void rejectsSelfTransfer() {
    assertThat(
            ledger
                .transfer(
                    alice,
                    new LedgerService.TransferInput(
                        a.toString(), "DEMO-ALICE", "10", "INR", "Self"),
                    "self")
                .status())
        .isEqualTo(422);
  }

  @Test
  void rejectsUnknownDestination() {
    assertThat(
            ledger
                .transfer(
                    alice,
                    new LedgerService.TransferInput(
                        a.toString(), "MISSING", "10", "INR", "Unknown"),
                    "missing")
                .status())
        .isEqualTo(422);
  }

  @Test
  void rejectsUnsupportedCurrency() {
    assertThatThrownBy(
            () ->
                ledger.transfer(
                    alice,
                    new LedgerService.TransferInput(a.toString(), "DEMO-BOB", "10", "USD", "FX"),
                    "fx"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void freezeStopsTransfers() {
    ledger.freeze(operator, a, true, "Investigation");
    assertThat(transfer("10", "frozen").status()).isEqualTo(422);
    ledger.freeze(operator, a, false, "Reviewed");
    assertThat(transfer("10", "unfrozen").status()).isEqualTo(201);
  }

  @Test
  void customerCannotFreeze() {
    assertThatThrownBy(() -> ledger.freeze(alice, a, true, "Own request"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void failureAfterFirstWriteRollsBackEverything() {
    int journals = count("journals"),
        audit = count("audit_events"),
        outbox = count("outbox_events");
    doThrow(new IllegalStateException("Injected crash"))
        .when(postingProbe)
        .afterFirstBalanceWrite();
    assertThatThrownBy(() -> transfer("500", "crash")).hasMessageContaining("Injected crash");
    assertThat(balance(a)).isEqualByComparingTo("5000");
    assertThat(balance(b)).isEqualByComparingTo("1000");
    assertThat(count("journals")).isEqualTo(journals);
    assertThat(count("transfers")).isZero();
    assertThat(count("idempotency_requests")).isZero();
    assertThat(count("audit_events")).isEqualTo(audit);
    assertThat(count("outbox_events")).isEqualTo(outbox);
    reset(postingProbe);
    assertThat(transfer("500", "crash").status()).isEqualTo(201);
  }

  @Test
  void concurrentSpendingCannotOverspend() throws Exception {
    ledger.transfer(
        alice,
        new LedgerService.TransferInput(a.toString(), "DEMO-BOB", "4000", "INR", "Setup"),
        "setup");
    var results = parallel(20, i -> transfer("100", "parallel-" + i));
    assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(10);
    assertThat(results.stream().filter(r -> r.status() == 422).count()).isEqualTo(10);
    assertThat(balance(a)).isEqualByComparingTo("0");
    assertThat(balance(b)).isEqualByComparingTo("6000");
  }

  @Test
  void fiftyConcurrentRetriesHaveOneEffect() throws Exception {
    var results = parallel(50, i -> transfer("1", "parallel-same"));
    assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
    assertThat(results.stream().map(r -> r.body().get("id")).distinct().count()).isEqualTo(1);
    assertThat(count("transfers")).isEqualTo(1);
  }

  @Test
  void oppositeDirectionTransfersPreserveBalances() throws Exception {
    parallel(
        20,
        i ->
            i % 2 == 0
                ? transfer("10", "ab-" + i)
                : ledger.transfer(
                    bob,
                    new LedgerService.TransferInput(
                        b.toString(), "DEMO-ALICE", "10", "INR", "Return"),
                    "ba-" + i));
    assertThat(balance(a)).isEqualByComparingTo("5000");
    assertThat(balance(b)).isEqualByComparingTo("1000");
  }

  private List<LedgerService.Result> parallel(
      int size, java.util.function.IntFunction<LedgerService.Result> action) throws Exception {
    var ready = new CountDownLatch(size);
    var go = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<LedgerService.Result>>();
      for (int i = 0; i < size; i++) {
        final int index = i;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return action.apply(index);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var results = new ArrayList<LedgerService.Result>();
      for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
      return results;
    }
  }

  @Test
  void commitRejectsEmptyJournal() {
    int before = count("journals");
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        s.db.update(
                            "INSERT INTO journals(id,reference,event_type,currency,description)"
                                + " VALUES(?,'BAD','TRANSFER','INR','Empty')",
                            UUID.randomUUID())))
        .isInstanceOf(RuntimeException.class);
    assertThat(count("journals")).isEqualTo(before);
  }

  @Test
  void commitRejectsUnbalancedJournal() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      UUID id = UUID.randomUUID();
                      s.db.update(
                          "INSERT INTO journals(id,reference,event_type,currency,description)"
                              + " VALUES(?,'BAD','TRANSFER','INR','Invalid')",
                          id);
                      s.db.update(
                          "INSERT INTO ledger_entries(id,journal_id,account_id,side,amount)"
                              + " VALUES(?,?,?,'DEBIT',10)",
                          UUID.randomUUID(),
                          id,
                          a);
                      s.db.update(
                          "INSERT INTO ledger_entries(id,journal_id,account_id,side,amount)"
                              + " VALUES(?,?,?,'CREDIT',9)",
                          UUID.randomUUID(),
                          id,
                          b);
                    }))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void applicationCannotModifyEvidence() {
    assertThatThrownBy(() -> s.db.update("UPDATE journals SET description='Changed'"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> s.db.update("DELETE FROM ledger_entries"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> s.db.update("DELETE FROM audit_events"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void detectsCorruptedProjection() {
    owner.update("UPDATE account_balances SET balance=balance+1 WHERE account_id=?", a);
    var run =
        recon.run(operator, null, Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));
    assertThat(run.get("exception_count")).isEqualTo(1);
    assertThat(
            s.one(
                    "SELECT category FROM reconciliation_exceptions WHERE run_id=?",
                    Support.uuid(run.get("id")))
                .get("category"))
        .isEqualTo("BALANCE_MISMATCH");
  }

  String statement(String ref, String outgoing, String incoming, String currency) {
    String now = Instant.now().toString();
    return "external_reference,account_ref,booking_time,signed_amount,currency\n"
        + ref
        + ",DEMO-ALICE,"
        + now
        + ","
        + outgoing
        + ",INR\n"
        + ref
        + ",DEMO-BOB,"
        + now
        + ","
        + incoming
        + ","
        + currency
        + "\n";
  }

  Map<String, Object> compare(String csv) {
    var imported =
        recon.importStatement(operator, "TEST", "test.csv", csv.getBytes(StandardCharsets.UTF_8));
    return recon.run(
        operator,
        Support.uuid(imported.get("id")),
        Instant.now().minusSeconds(60),
        Instant.now().plusSeconds(2));
  }

  @Test
  void exactStatementMatchesBothLegs() {
    var transfer = transfer("500", "statement");
    var run = compare(statement(transfer.body().get("reference").toString(), "-500", "500", "INR"));
    assertThat(run.get("matched_count")).isEqualTo(2);
    assertThat(run.get("exception_count")).isEqualTo(0);
  }

  @Test
  void amountMismatchHasEvidence() {
    var t = transfer("500", "statement");
    var run = compare(statement(t.body().get("reference").toString(), "-499", "500", "INR"));
    var e =
        s.one(
            "SELECT * FROM reconciliation_exceptions WHERE run_id=?", Support.uuid(run.get("id")));
    assertThat(e.get("category")).isEqualTo("AMOUNT_MISMATCH");
    assertThat(e.get("snapshot_item_id")).isNotNull();
    assertThat(e.get("statement_row_id")).isNotNull();
  }

  @Test
  void currencyMismatchIsDistinct() {
    var t = transfer("500", "statement");
    compare(statement(t.body().get("reference").toString(), "-500", "500", "USD"));
    assertThat(s.one("SELECT category FROM reconciliation_exceptions").get("category"))
        .isEqualTo("CURRENCY_MISMATCH");
  }

  @Test
  void directionMismatchIsDistinct() {
    var t = transfer("500", "statement");
    compare(statement(t.body().get("reference").toString(), "500", "500", "INR"));
    assertThat(s.one("SELECT category FROM reconciliation_exceptions").get("category"))
        .isEqualTo("DIRECTION_MISMATCH");
  }

  @Test
  void missingLegDetected() {
    var t = transfer("500", "statement");
    String csv = statement(t.body().get("reference").toString(), "-500", "500", "INR");
    compare(csv.substring(0, csv.lastIndexOf("TX-")));
    assertThat(s.one("SELECT category FROM reconciliation_exceptions").get("category"))
        .isEqualTo("MISSING_STATEMENT_LEG");
  }

  @Test
  void duplicatesPreservedAndFlagged() {
    var t = transfer("500", "statement");
    String csv = statement(t.body().get("reference").toString(), "-500", "500", "INR");
    compare(csv + csv.lines().skip(1).findFirst().orElseThrow() + "\n");
    assertThat(s.one("SELECT category FROM reconciliation_exceptions").get("category"))
        .isEqualTo("DUPLICATE_STATEMENT_LEG");
    assertThat(count("statement_rows")).isEqualTo(3);
  }

  @Test
  void importIsDeduplicated() {
    byte[] csv = statement("external", "-1", "1", "INR").getBytes(StandardCharsets.UTF_8);
    var first = recon.importStatement(operator, "TEST", "a.csv", csv);
    var second = recon.importStatement(operator, "TEST", "b.csv", csv);
    assertThat(second.get("id")).isEqualTo(first.get("id"));
    assertThat(second.get("duplicate")).isEqualTo(true);
    assertThat(count("statement_imports")).isEqualTo(1);
  }

  @Test
  void malformedRowsAreEvidence() {
    compare(
        "external_reference,account_ref,booking_time,signed_amount,currency\n"
            + "x,DEMO-ALICE,broken,NaN,INR\n");
    assertThat(s.one("SELECT category FROM reconciliation_exceptions").get("category"))
        .isEqualTo("INVALID_ROW");
  }

  @Test
  void malformedHeadersRollBackImport() {
    assertThatThrownBy(() -> compare("wrong,headers\na,b\n")).isInstanceOf(ApiException.class);
    assertThat(count("statement_imports")).isZero();
  }

  @Test
  void statementOnlyLegsDetected() {
    compare(statement("UNKNOWN", "-500", "500", "INR"));
    assertThat(count("reconciliation_exceptions")).isEqualTo(2);
  }

  @Test
  void rerunPreservesPriorRun() {
    var t = transfer("5", "run");
    var imported =
        recon.importStatement(
            operator,
            "TEST",
            "a.csv",
            statement(t.body().get("reference").toString(), "-5", "5", "INR")
                .getBytes(StandardCharsets.UTF_8));
    for (int i = 0; i < 2; i++)
      recon.run(
          operator,
          Support.uuid(imported.get("id")),
          Instant.now().minusSeconds(60),
          Instant.now().plusSeconds(1));
    assertThat(count("reconciliation_runs")).isEqualTo(2);
    assertThat(count("reconciliation_snapshot_items")).isEqualTo(4);
  }

  @Test
  void fullReversalKeepsOriginalAndRestoresBalances() {
    var t = transfer("500", "reverse");
    UUID request = ledger.requestCorrection(operator, journal(t), null, "Wrong recipient");
    var result = ledger.decideCorrection(approver, request, true, "Reviewed against evidence");
    assertThat(result.get("state")).isEqualTo("APPROVED");
    assertThat(balance(a)).isEqualByComparingTo("5000");
    assertThat(balance(b)).isEqualByComparingTo("1000");
    assertThat(count("transfers")).isEqualTo(1);
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM journals WHERE reversal_of=?", Integer.class, journal(t)))
        .isEqualTo(1);
  }

  @Test
  void aSecondDecisionCannotReverseAgain() {
    var t = transfer("500", "reverse");
    UUID request = ledger.requestCorrection(operator, journal(t), null, "Wrong recipient");
    ledger.decideCorrection(approver, request, true, "Reviewed");
    assertThatThrownBy(() -> ledger.decideCorrection(approver, request, true, "Again"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void spentRecipientFundsBlockReversal() {
    var t = transfer("500", "reverse");
    ledger.transfer(
        bob,
        new LedgerService.TransferInput(b.toString(), "DEMO-ALICE", "1500", "INR", "Spent"),
        "spend");
    UUID request = ledger.requestCorrection(operator, journal(t), null, "Wrong recipient");
    assertThatThrownBy(() -> ledger.decideCorrection(approver, request, true, "Reviewed"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("spent");
    assertThat(s.one("SELECT state FROM correction_requests WHERE id=?", request).get("state"))
        .isEqualTo("PENDING");
  }

  @Test
  void preventsSelfApprovalEvenWithChangedRole() {
    var t = transfer("5", "reverse");
    UUID request = ledger.requestCorrection(operator, journal(t), null, "Review");
    Actor sameUser = new Actor(operator.id(), operator.email(), "APPROVER", operator.name());
    assertThatThrownBy(() -> ledger.decideCorrection(sameUser, request, true, "Self"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("different staff");
  }

  @Test
  void auditorCannotPostCorrection() {
    var t = transfer("5", "reverse");
    assertThatThrownBy(() -> ledger.requestCorrection(auditor, journal(t), null, "Read only"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void outboxConsumerDeduplicatesReplay() {
    assertThat(worker.processEvent()).isTrue();
    var first = s.one("SELECT * FROM outbox_events WHERE state='DELIVERED' LIMIT 1");
    UUID event = Support.uuid(first.get("id"));
    s.db.update("UPDATE outbox_events SET state='PENDING' WHERE id=?", event);
    while (worker.processEvent()) {}
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM notifications WHERE event_id=?", Integer.class, event))
        .isEqualTo(1);
  }

  @Test
  void workerFailureRollsBackConsumerAndRetriesDurably() {
    doThrow(new IllegalStateException("Injected delivery crash"))
        .when(deliveryProbe)
        .afterConsumerWrite();
    assertThat(worker.processEvent()).isFalse();
    assertThat(count("notifications")).isZero();
    assertThat(count("processed_events")).isZero();
    assertThat(s.db.queryForObject("SELECT max(attempts) FROM outbox_events", Integer.class))
        .isEqualTo(1);
    reset(deliveryProbe);
    s.db.update("UPDATE outbox_events SET next_attempt_at=now()");
    assertThat(worker.processEvent()).isTrue();
    assertThat(count("notifications")).isEqualTo(1);
  }

  @Test
  void scheduledTransferExecutesOnce() {
    var job =
        jobs.schedule(
            alice,
            Map.of(
                "sourceAccountId",
                a.toString(),
                "destinationAccountRef",
                "DEMO-BOB",
                "amount",
                "50",
                "description",
                "Later",
                "dueAt",
                Instant.now().plusSeconds(60).toString()),
            "schedule-key");
    UUID id = Support.uuid(job.get("id"));
    s.db.update("UPDATE scheduled_transfers SET due_at=now(),next_attempt_at=now() WHERE id=?", id);
    assertThat(worker.processSchedule()).isTrue();
    assertThat(worker.processSchedule()).isFalse();
    assertThat(balance(a)).isEqualByComparingTo("4950");
    assertThat(count("transfers")).isEqualTo(1);
  }

  @Test
  void scheduleCreationReplaysAndCancelPreventsExecution() {
    var data =
        Map.of(
            "sourceAccountId",
            a.toString(),
            "destinationAccountRef",
            "DEMO-BOB",
            "amount",
            "50",
            "description",
            "Later",
            "dueAt",
            Instant.now().plusSeconds(60).toString());
    var first = jobs.schedule(alice, data, "schedule");
    var replay = jobs.schedule(alice, data, "schedule");
    assertThat(replay.get("id")).isEqualTo(first.get("id"));
    UUID id = Support.uuid(first.get("id"));
    jobs.cancel(alice, id);
    assertThat(worker.processSchedule()).isFalse();
    assertThat(count("transfers")).isZero();
  }

  @Test
  void csrfAndAuthorizationAreEnforced() throws Exception {
    mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/accounts")
                .with(user(alice.email()).roles("CUSTOMER"))
                .contentType("application/json")
                .content("{\"name\":\"Extra\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/accounts/" + b).with(user(alice.email()).roles("CUSTOMER")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/audit/events").with(user(alice.email()).roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/accounts")
                .with(user(alice.email()).roles("CUSTOMER"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"Extra\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void registrationCannotGrantStaffRole() {
    var result =
        identities.register(
            Map.of(
                "email",
                "new@example.test",
                "password",
                "LongPassword!2026",
                "name",
                "New User",
                "role",
                "APPROVER"));
    assertThat(result.get("role")).isEqualTo("CUSTOMER");
  }

  @Test
  void loginAndLogoutUseRealSession() throws Exception {
    var login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("email", alice.email())
                    .param("password", "TestPassword!2026"))
            .andExpect(status().isOk())
            .andReturn();
    String sessionHeader =
        login.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .findFirst()
            .orElseThrow();
    var cookie = org.springframework.mock.web.MockCookie.parse(sessionHeader);
    mvc.perform(get("/api/v1/auth/me").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(alice.email()));
    mvc.perform(post("/api/v1/auth/logout").cookie(cookie).with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  void freezeRacingTransferHasAConsistentOutcome() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var transfer =
          executor.submit(
              () -> {
                barrier.await();
                return transfer("10", "freeze-race");
              });
      var freeze =
          executor.submit(
              () -> {
                barrier.await();
                ledger.freeze(operator, a, true, "Concurrent review");
                return true;
              });
      var result = transfer.get(15, TimeUnit.SECONDS);
      assertThat(freeze.get(15, TimeUnit.SECONDS)).isTrue();
      assertThat(balance(a)).isEqualByComparingTo(result.status() == 201 ? "4990" : "5000");
      assertThat(s.one("SELECT status FROM ledger_accounts WHERE id=?", a).get("status"))
          .isEqualTo("FROZEN");
    }
  }

  @Test
  void simultaneousApprovalsProduceOneReversal() throws Exception {
    var posted = transfer("10", "approve-race");
    UUID request = ledger.requestCorrection(operator, journal(posted), null, "Concurrent review");
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<Boolean> decide =
          () -> {
            barrier.await();
            try {
              ledger.decideCorrection(approver, request, true, "Reviewed independently");
              return true;
            } catch (ApiException ex) {
              assertThat(ex.code()).isEqualTo("ALREADY_DECIDED");
              return false;
            }
          };
      var first = executor.submit(decide);
      var second = executor.submit(decide);
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM journals WHERE reversal_of=?",
                Integer.class,
                journal(posted)))
        .isEqualTo(1);
    assertThat(balance(a)).isEqualByComparingTo("5000");
  }

  @Test
  void missingIdempotencyHeaderIsABadRequest() throws Exception {
    mvc.perform(
            post("/api/v1/transfers")
                .with(user(alice.email()).roles("CUSTOMER"))
                .with(csrf())
                .contentType("application/json")
                .content(s.json(input("10"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void csvExportsNeutralizeMultilineFormulaDescriptions() throws Exception {
    ledger.transfer(
        alice,
        new LedgerService.TransferInput(a.toString(), "DEMO-BOB", "1", "INR", "=SUM(1,2)\nunsafe"),
        "formula");
    var response =
        mvc.perform(
                get("/api/v1/accounts/" + a + "/statement.csv")
                    .with(user(alice.email()).roles("CUSTOMER")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).contains("\"'=SUM(1,2)\nunsafe\"");
  }

  @Test
  void randomizedBidirectionalSequencePreservesTheLedger() {
    var random = new Random(76123891);
    for (int i = 0; i < 50; i++) {
      boolean forward = random.nextBoolean();
      String amount = new BigDecimal(random.nextInt(1000) + 1).movePointLeft(2).toPlainString();
      var result =
          ledger.transfer(
              forward ? alice : bob,
              new LedgerService.TransferInput(
                  (forward ? a : b).toString(),
                  forward ? "DEMO-BOB" : "DEMO-ALICE",
                  amount,
                  "INR",
                  "Seeded sequence"),
              "random-" + i);
      assertThat(result.status()).isEqualTo(201);
    }
    assertThat(balance(a).add(balance(b))).isEqualByComparingTo("6000");
    assertThat(
            recon
                .run(operator, null, Instant.now().minusSeconds(60), Instant.now().plusSeconds(1))
                .get("exception_count"))
        .isEqualTo(0);
  }

  @Test
  void scheduleWithInsufficientFundsIsTerminallyRejected() {
    var job =
        jobs.schedule(
            alice,
            Map.of(
                "sourceAccountId",
                a.toString(),
                "destinationAccountRef",
                "DEMO-BOB",
                "amount",
                "1000000",
                "description",
                "Insufficient at execution",
                "dueAt",
                Instant.now().plusSeconds(60).toString()),
            "insufficient-schedule");
    UUID id = Support.uuid(job.get("id"));
    s.db.update("UPDATE scheduled_transfers SET due_at=now(),next_attempt_at=now() WHERE id=?", id);
    assertThat(worker.processSchedule()).isTrue();
    assertThat(s.one("SELECT state FROM scheduled_transfers WHERE id=?", id).get("state"))
        .isEqualTo("REJECTED");
    assertThat(worker.processSchedule()).isFalse();
    assertThat(count("transfers")).isZero();
  }
}
