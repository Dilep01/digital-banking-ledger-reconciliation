package dev.ledger.identity;

import dev.ledger.ledger.LedgerService;
import dev.ledger.shared.Support;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.demo", havingValue = "true")
public class DemoData implements ApplicationRunner {
  private final Support s;
  private final PasswordEncoder encoder;
  private final LedgerService ledger;
  private final String password;

  public DemoData(
      Support s,
      PasswordEncoder encoder,
      LedgerService ledger,
      @Value("${app.demo-password}") String password) {
    this.s = s;
    this.encoder = encoder;
    this.ledger = ledger;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    s.db.execute("SELECT pg_advisory_xact_lock(76123891)");
    s.db.update(
        "INSERT INTO ledger_accounts(id,public_ref,name,account_type,normal_side)"
            + " VALUES(?,'SYSTEM-CASH','Simulated cash asset','CASH','DEBIT') ON CONFLICT DO"
            + " NOTHING",
        LedgerService.CASH);
    s.db.update(
        "INSERT INTO account_balances(account_id) VALUES(?) ON CONFLICT DO NOTHING",
        LedgerService.CASH);
    user("alice", "Alice Morgan", "CUSTOMER", "DEMO-ALICE", "5000.00");
    user("bob", "Bob Williams", "CUSTOMER", "DEMO-BOB", "1000.00");
    user("operator", "Priya · Operations", "OPERATOR", null, null);
    user("approver", "Sam · Approvals", "APPROVER", null, null);
    user("auditor", "Alex · Audit", "AUDITOR", null, null);
  }

  private void user(String login, String name, String role, String ref, String funds) {
    String email = login + "@ledger.local";
    UUID id = UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8));
    s.db.update(
        "INSERT INTO users(id,email,display_name,password_hash,role) VALUES(?,?,?,?,?) ON"
            + " CONFLICT(email) DO NOTHING",
        id,
        email,
        name,
        encoder.encode(password),
        role);
    if (ref != null
        && s.db.queryForObject(
                "SELECT count(*) FROM ledger_accounts WHERE public_ref=?", Integer.class, ref)
            == 0) {
      UUID account = ledger.createAccountFor(id, name.split(" ")[0] + " · Everyday", ref);
      ledger.fundDemo(id, account, new BigDecimal(funds), "OPEN-" + login.toUpperCase(Locale.ROOT));
    }
  }
}
