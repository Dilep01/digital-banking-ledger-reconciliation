CREATE TABLE users (
 id UUID PRIMARY KEY, email VARCHAR(180) NOT NULL UNIQUE, display_name VARCHAR(80) NOT NULL,
 password_hash VARCHAR(100) NOT NULL, role VARCHAR(20) NOT NULL CHECK(role IN ('CUSTOMER','OPERATOR','APPROVER','AUDITOR')),
 enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE ledger_accounts (
 id UUID PRIMARY KEY, owner_id UUID REFERENCES users(id), public_ref VARCHAR(45) UNIQUE NOT NULL,
 name VARCHAR(80) NOT NULL, account_type VARCHAR(15) NOT NULL CHECK(account_type IN ('DEPOSIT','CASH')),
 normal_side VARCHAR(6) NOT NULL CHECK(normal_side IN ('DEBIT','CREDIT')),
 status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 currency VARCHAR(3) NOT NULL DEFAULT 'INR' CHECK(currency='INR'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK ((account_type='DEPOSIT' AND normal_side='CREDIT' AND owner_id IS NOT NULL) OR (account_type='CASH' AND normal_side='DEBIT' AND owner_id IS NULL))
);
CREATE INDEX accounts_owner ON ledger_accounts(owner_id);
CREATE TABLE account_balances (
 account_id UUID PRIMARY KEY REFERENCES ledger_accounts(id),
 balance NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK(balance >= 0 AND balance <> 'NaN'::numeric),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE journals (
 id UUID PRIMARY KEY, reference VARCHAR(55) UNIQUE NOT NULL, event_type VARCHAR(15) NOT NULL CHECK(event_type IN ('FUNDING','TRANSFER','REVERSAL')),
 currency VARCHAR(3) NOT NULL CHECK(currency='INR'), reversal_of UUID UNIQUE REFERENCES journals(id),
 description VARCHAR(160) NOT NULL, posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK ((event_type='REVERSAL') = (reversal_of IS NOT NULL))
);
CREATE TABLE ledger_entries (
 id UUID PRIMARY KEY, journal_id UUID NOT NULL REFERENCES journals(id), account_id UUID NOT NULL REFERENCES ledger_accounts(id),
 side VARCHAR(6) NOT NULL CHECK(side IN ('DEBIT','CREDIT')),
 amount NUMERIC(19,2) NOT NULL CHECK(amount>0 AND amount <> 'NaN'::numeric),
 UNIQUE(journal_id,account_id)
);
CREATE INDEX entries_account ON ledger_entries(account_id,journal_id);
CREATE INDEX journals_history ON journals(posted_at,id);
CREATE TABLE transfers (
 id UUID PRIMARY KEY, actor_id UUID NOT NULL REFERENCES users(id), source_id UUID NOT NULL REFERENCES ledger_accounts(id),
 destination_id UUID NOT NULL REFERENCES ledger_accounts(id), amount NUMERIC(19,2) NOT NULL CHECK(amount>0 AND amount <> 'NaN'::numeric),
 journal_id UUID UNIQUE NOT NULL REFERENCES journals(id), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK(source_id<>destination_id)
);
CREATE TABLE idempotency_requests (
 user_id UUID NOT NULL REFERENCES users(id), operation VARCHAR(20) NOT NULL, request_key VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, http_status INT, response_body TEXT, transfer_id UUID REFERENCES transfers(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(user_id,operation,request_key)
);
CREATE TABLE audit_events (
 id UUID PRIMARY KEY, actor_id UUID REFERENCES users(id), action VARCHAR(50) NOT NULL, target VARCHAR(100) NOT NULL,
 outcome VARCHAR(15) NOT NULL, reason VARCHAR(300) NOT NULL, correlation_id VARCHAR(64) NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX audit_time ON audit_events(occurred_at DESC);
CREATE FUNCTION deny_evidence_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Posted evidence is append-only' USING ERRCODE='23514'; END $$;
CREATE TRIGGER journal_immutable BEFORE UPDATE OR DELETE ON journals FOR EACH ROW EXECUTE FUNCTION deny_evidence_change();
CREATE TRIGGER entry_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION deny_evidence_change();
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION deny_evidence_change();
CREATE FUNCTION validate_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid UUID; n INT; net NUMERIC; mismatch INT;
BEGIN
 IF TG_TABLE_NAME='journals' THEN jid:=NEW.id; ELSE jid:=NEW.journal_id; END IF;
 SELECT count(*), coalesce(sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END),0)
 INTO n,net FROM ledger_entries WHERE journal_id=jid;
 SELECT count(*) INTO mismatch FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id
 JOIN journals j ON j.id=e.journal_id WHERE j.id=jid AND a.currency<>j.currency;
 IF n<2 OR net<>0 OR mismatch<>0 THEN RAISE EXCEPTION 'Journal % must have balanced entries in one currency',jid USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_balance_header AFTER INSERT ON journals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_journal();
CREATE CONSTRAINT TRIGGER journal_balance_entries AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_journal();

CREATE TABLE statement_imports (
 id UUID PRIMARY KEY, source VARCHAR(60) NOT NULL, file_hash VARCHAR(64) NOT NULL, original_name VARCHAR(120) NOT NULL,
 original_content TEXT NOT NULL, parser_version VARCHAR(20) NOT NULL DEFAULT 'csv-v1', uploaded_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE(source,file_hash)
);
CREATE TABLE statement_rows (
 id UUID PRIMARY KEY, import_id UUID NOT NULL REFERENCES statement_imports(id), line_number INT NOT NULL,
 external_reference VARCHAR(100), account_ref VARCHAR(100), signed_amount NUMERIC(19,2), currency VARCHAR(10),
 booking_time TIMESTAMPTZ, validation_error VARCHAR(250), raw_row TEXT NOT NULL, UNIQUE(import_id,line_number)
);
CREATE TABLE reconciliation_runs (
 id UUID PRIMARY KEY, import_id UUID REFERENCES statement_imports(id), kind VARCHAR(12) NOT NULL CHECK(kind IN ('INTERNAL','STATEMENT')),
 state VARCHAR(15) NOT NULL DEFAULT 'COMPLETED', actor_id UUID NOT NULL REFERENCES users(id),
 interval_start TIMESTAMPTZ NOT NULL, interval_end TIMESTAMPTZ NOT NULL, snapshot_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 algorithm_version VARCHAR(25) NOT NULL DEFAULT 'exact-leg-v1', matched_count INT NOT NULL DEFAULT 0,
 exception_count INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), CHECK(interval_end>interval_start)
);
CREATE TABLE reconciliation_snapshot_items (
 id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES reconciliation_runs(id), entry_id UUID NOT NULL REFERENCES ledger_entries(id),
 reference VARCHAR(55) NOT NULL, account_ref VARCHAR(45) NOT NULL, signed_amount NUMERIC(19,2) NOT NULL, currency VARCHAR(3) NOT NULL
);
CREATE TABLE reconciliation_exceptions (
 id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES reconciliation_runs(id), category VARCHAR(40) NOT NULL,
 reference VARCHAR(100) NOT NULL, detail TEXT NOT NULL, statement_row_id UUID REFERENCES statement_rows(id),
 snapshot_item_id UUID REFERENCES reconciliation_snapshot_items(id),
 state VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','INVESTIGATING','AWAITING_APPROVAL','RESOLVED','DISMISSED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE exception_events (
 id UUID PRIMARY KEY, exception_id UUID NOT NULL REFERENCES reconciliation_exceptions(id), actor_id UUID NOT NULL REFERENCES users(id),
 state VARCHAR(20) NOT NULL, note VARCHAR(500) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER exception_event_immutable BEFORE UPDATE OR DELETE ON exception_events FOR EACH ROW EXECUTE FUNCTION deny_evidence_change();
CREATE TABLE correction_requests (
 id UUID PRIMARY KEY, journal_id UUID NOT NULL REFERENCES journals(id), exception_id UUID REFERENCES reconciliation_exceptions(id),
 requester_id UUID NOT NULL REFERENCES users(id), reason VARCHAR(300) NOT NULL, approver_id UUID REFERENCES users(id),
 state VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPROVED','REJECTED')),
 decision_reason VARCHAR(300), reversal_id UUID REFERENCES journals(id), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK(approver_id IS NULL OR approver_id<>requester_id)
);
CREATE UNIQUE INDEX one_pending_correction ON correction_requests(journal_id) WHERE state='PENDING';
CREATE TABLE outbox_events (
 id UUID PRIMARY KEY, actor_id UUID NOT NULL REFERENCES users(id), aggregate_id UUID NOT NULL,
 event_type VARCHAR(30) NOT NULL, payload TEXT NOT NULL, state VARCHAR(15) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), last_error VARCHAR(250),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX outbox_pending ON outbox_events(state,next_attempt_at);
CREATE TABLE processed_events (consumer_id VARCHAR(40) NOT NULL,event_id UUID NOT NULL REFERENCES outbox_events(id),processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(consumer_id,event_id));
CREATE TABLE notifications (id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),event_id UUID UNIQUE NOT NULL REFERENCES outbox_events(id),message VARCHAR(250) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp());
CREATE TABLE scheduled_transfers (
 id UUID PRIMARY KEY, actor_id UUID NOT NULL REFERENCES users(id), source_id UUID NOT NULL REFERENCES ledger_accounts(id),
 destination_ref VARCHAR(45) NOT NULL, amount NUMERIC(19,2) NOT NULL CHECK(amount>0 AND amount<='1000000'::numeric),
 description VARCHAR(160) NOT NULL, due_at TIMESTAMPTZ NOT NULL, state VARCHAR(15) NOT NULL DEFAULT 'PENDING'
 CHECK(state IN ('PENDING','COMPLETED','REJECTED','CANCELLED','FAILED')), attempts INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMPTZ NOT NULL, result TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE login_buckets (bucket_key VARCHAR(100) PRIMARY KEY,attempts INT NOT NULL,expires_at TIMESTAMPTZ NOT NULL);

CREATE TABLE spring_session (
 primary_id CHAR(36) NOT NULL PRIMARY KEY, session_id CHAR(36) NOT NULL UNIQUE, creation_time BIGINT NOT NULL,
 last_access_time BIGINT NOT NULL, max_inactive_interval INT NOT NULL, expiry_time BIGINT NOT NULL, principal_name VARCHAR(100)
);
CREATE INDEX spring_session_expiry ON spring_session(expiry_time);
CREATE INDEX spring_session_principal ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
 session_primary_id CHAR(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
 attribute_name VARCHAR(200) NOT NULL, attribute_bytes BYTEA NOT NULL, PRIMARY KEY(session_primary_id,attribute_name)
);
GRANT USAGE ON SCHEMA public TO ledger_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO ledger_app;
REVOKE UPDATE,DELETE,TRUNCATE ON journals,ledger_entries,audit_events,exception_events,reconciliation_snapshot_items,statement_rows,statement_imports FROM ledger_app;
