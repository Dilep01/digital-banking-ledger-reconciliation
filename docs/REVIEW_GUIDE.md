# Final review guide

29 October 2026

## Open the application

Use the packaged workspace at **http://127.0.0.1:8080**. If it is not running, execute `scripts/start.ps1` from the project folder. Start/build/stop instructions and demo credentials are in the root README.

Choose a demo identity on the login screen, then click Sign in. The password is `DemoBank!2026`. Use different identities for Operations and Approver; there is no role-switching shortcut inside an authenticated session.

## Review the customer experience

As `alice@ledger.local`:

1. Check the account balance and recent activity. The database contains synthetic browser-test and reconciliation-demo activity, so its balance can differ from the original INR 5,000 opening seed.
2. Open Accounts and copy the `DEMO-ALICE` reference.
3. Transfer `10.00` to `DEMO-BOB`. The review screen displays the exact intent before posting.
4. Check Activity and download a CSV statement.
5. Schedule a small future transfer. Funds are checked at due time; scheduling does not reserve them. A pending schedule can be cancelled.
6. Sign out and sign in as Bob to inspect the incoming side.

The automated browser test also deliberately drops one response after the server commits, then retries the saved request. It verifies that only one transfer was added.

## Review reconciliation

Sign in as `operator@ledger.local` and open Reconciliation.

The generated review batch has two imports with filenames beginning `clean-` and `anomalies-`. Their comparison runs are already saved:

- Clean run: four account legs matched, zero exceptions.
- Anomaly run: one matched leg and four exceptions — amount mismatch, duplicate statement leg, missing statement leg, and statement-only entry.
- Internal integrity run: zero findings.

Open a run ID, then Investigate on a finding. The dialog shows the statement line, internal snapshot leg when available, and prior investigation events. Add a note or dismiss an explainable exception with a reason. Financial resolution requires a supported reviewed correction.

The demo generation script defines expected amounts before posting. It uses returned references to identify operations; it does not derive expected amounts by querying the ledger. Its manifest and generated CSVs are under `.runtime/generated-statements`, and its result summary is copied under `docs/evidence` after verification.

Importing the same file/source again selects the existing evidence. Rerunning reconciliation creates a separate run; it does not replace the old findings or move money.

## Review a full reversal

1. As Operations, open Ledger explorer and locate an original TRANSFER journal.
2. Inspect its entries, then Request reversal and supply a reason.
3. Sign out. Sign in as `approver@ledger.local` and open Approvals.
4. Review the request and supply a decision reason.
5. Approve only if the recipient still has sufficient funds and both accounts are active.
6. Inspect the original journal and the new REVERSAL journal.

An approved reversal does not edit the original posting. A repeated decision cannot reverse it twice. A blocked reversal stays pending for investigation. Some browser-created requests may intentionally remain pending from earlier validation; they are visible review records, not missing financial entries.

## Review audit and background work

The auditor identity can inspect the ledger, runs, audit events, and restricted Actuator metrics. It cannot initiate transfers, freeze accounts, or approve a correction.

Background activity shows outbox delivery and scheduled work. The local worker delivers inbox updates, retries unexpected failures, and exposes failed outbox items for operator requeue. There is no actual email/SMS delivery or external message broker.

## Review the code

Suggested reading order:

1. `docs/decisions/001-implementation-stack.md` for choices and departures from the original plan.
2. `backend/src/main/resources/db/migration` for constraints, permissions, and append-only evidence.
3. `backend/src/main/java/dev/ledger/ledger/LedgerService.java` for posting, idempotency, lock order, and reversal checks.
4. `backend/src/main/java/dev/ledger/reconciliation/ReconciliationService.java` for preserved imports and deterministic matching.
5. `backend/src/main/java/dev/ledger/jobs` for durable claims, retries, and consumer deduplication.
6. `frontend/src/features` for customer and staff screens; `frontend/src/shared` for shared UI/types.
7. `backend/src/test`, `frontend/src/api.test.ts`, and `frontend/e2e` for executable evidence.
8. `docs/openapi.yaml` for the validated API contract.

The root README links the verification report and operations guide. This release is a local synthetic project; it is not a public deployment or a production banking certification.

## Phase completion map

| Phase | Delivered result |
| --- | --- |
| 0 | Installed-tool verification and isolated PostgreSQL setup |
| 1 | Backend/frontend skeleton, migrations, configuration, packaged UI |
| 2 | Identity, sessions, roles, ownership, account creation |
| 3 | Double-entry ledger, exact balances, balanced demo funding |
| 4 | Atomic transfers, locking, idempotency, rollback and concurrency checks |
| 5 | Customer dashboard, transfers, activity, statements, mobile navigation |
| 6 | Internal checks, synthetic statement comparison, snapshots and exceptions |
| 7 | Investigations, separate approvals, full reversals, audit views |
| 8 | Automated checks, release packaging, local operations, backup/restore |
| 9 | Two-instance verification, shared sessions, outbox and restart recovery |
| 10 | Scheduled transfers as the selected specialization |

Optional alternatives listed in the blueprint, such as FX, holds, Kubernetes, brokers, and public hosting, are not part of this implementation.
