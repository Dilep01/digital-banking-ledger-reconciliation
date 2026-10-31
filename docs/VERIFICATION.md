# Verification record

Collected 28–29 October 2026. The application uses synthetic data and local processes only.

## Checked release

| Check | Result |
| --- | --- |
| Backend financial/security/integration suite | 47 passed, zero failures/errors/skips |
| Backend amount-validation suite | 15 passed, zero failures/errors/skips |
| Frontend exact-display/request-outcome tests | 7 passed |
| Strict TypeScript and Vite production build | Passed |
| Packaged application browser workflows | 5 passed using Microsoft Edge |
| OpenAPI 3.1 contract | Validated, 33 paths |
| Fresh-database migrations | Applied successfully to development, test, and isolated two-instance databases |
| Two-instance shared-session/concurrency/recovery check | Passed |
| Backup restored into a new isolated database | Passed financial fingerprint comparison |
| Independent synthetic statement scenario | Clean and deliberately altered batches produced expected results |

The final full build ran through `scripts/build.ps1`. It validated the API contract, ran frontend tests, built React, ran the real-PostgreSQL backend suite, and packaged the combined executable JAR. The later browser checks used the packaged app at port 8080, including its security headers.

Machine-readable summaries are in [evidence/test-summary.json](evidence/test-summary.json), [evidence/cluster-result.json](evidence/cluster-result.json), [evidence/restore-result.json](evidence/restore-result.json), and [evidence/demo-scenario-result.json](evidence/demo-scenario-result.json). Full local build logs and failure traces remain under ignored `.runtime` and `frontend/test-results` directories.

Some server test logs intentionally contain stack traces from injected posting/delivery failures. The suite summaries report the actual outcome: those tests pass by verifying rollback and durable recovery, not by suppressing the failure.

## Financial evidence

The backend tests cover:

- Exact amount validation, positive bounds, and rejection of extra decimal places/non-finite input.
- Balanced transfer and funding journals, plus commit-time rejection of empty/unbalanced journals.
- Journal/audit edit restrictions for the application database role.
- Same-key replay, changed-intent conflict, saved insufficient-funds rejection, and ownership checks.
- Twenty simultaneous INR 100 debit requests competing for INR 1,000: ten post, ten reject, ending source balance zero.
- Fifty simultaneous copies of one request: one transfer and one financial effect.
- Opposite-direction concurrency and an account freeze racing a transfer.
- A failure after the first balance write: journals, entries, balances, transfer, audit, outbox, and key reservation roll back together.
- Concurrent approval attempts: one reversal; self-approval and repeated decisions fail.
- Spent recipient funds preventing reversal while the request remains pending.
- A seeded randomized bidirectional transfer sequence preserving balances and internal integrity.
- A corrupted balance projection producing an internal reconciliation finding.
- Statement matches, duplicate/missing legs, amount/currency/direction mismatches, malformed rows, duplicate imports, and preserved reruns.
- Session login/logout, CSRF enforcement, customer/staff authorization, registration role restrictions, and required idempotency headers.
- Spreadsheet formula protection in exported multiline descriptions.
- Outbox replay deduplication, injected delivery failure/retry, schedule cancellation, one-time execution, and terminal insufficient-funds rejection.

Concurrency tests use separate real PostgreSQL transactions, not an in-memory substitute. Commit-time trigger tests actually reach commit.

## Browser workflows

The five checks exercise:

1. Customer login, dashboard, reviewed transfer, account history, statement download, and logout.
2. Operations requesting a full reversal and a separate Approver posting it.
3. Statement upload, comparison, exception evidence, investigation note, and a clean internal check.
4. Mobile navigation and account access at a 390 × 844 viewport, with no page-width overflow.
5. A response deliberately dropped after a transfer commits, followed by safe retry of the same saved intent. The transfer count increases by one.

Screenshots in `docs/screenshots` were visually inspected. Screenshot capture disables finite navigation animation so a closing mobile sidebar is not captured partway through its transition.

## Two application instances

`scripts/cluster-check.ps1` starts two separate JVMs on ports 8081 and 8082, sharing a newly created isolated PostgreSQL database. It verifies shared authentication, database locking, idempotency, logout invalidation, and durable scheduled work.

The test stops the first process after it commits a schedule. The surviving process executes that schedule once. This is a local process-recovery demonstration; it is not database failover, cross-database settlement, or infrastructure high availability.

On the recorded Windows machine (AMD Ryzen 5 5600U, 12 logical CPUs, approximately 15 GiB usable RAM, Node 24.18.0), a burst of 50 same-key requests completed in 945 ms, with request p50 847 ms and p95 878 ms. These are replay-burst measurements from one synthetic run. They do not measure 50 independent monetary postings or establish production throughput.

## Synthetic review dataset

The demo generator defines INR 500 and INR 250 transfer expectations before submitting them. Returned references identify the operations; expected amounts are not copied from a ledger query.

- Clean comparison: four matched account legs and zero findings.
- Altered comparison: one matched leg and exactly four findings — AMOUNT_MISMATCH, DUPLICATE_STATEMENT_LEG, MISSING_STATEMENT_LEG, and STATEMENT_ONLY.
- Full internal integrity scan after the batch: zero findings.

Each imported file, line, internal snapshot, and run result is retained for review. Older browser-test statement exceptions and pending requests may also appear in the workspace; they remain attributable synthetic evidence.

## Backup restoration

The restore script creates a new database rather than overwriting development data. It compares journal/entry/audit counts, debit and credit totals, and a sorted balance fingerprint. The JSON evidence identifies the exact snapshot and restored database. The comparison ran without concurrent financial writes.

This establishes that the checked local snapshot restored consistently. It does not establish an off-machine backup strategy or a recovery-time guarantee.

## Tested versions and limits

The checked stack is Java 21.0.12, Spring Boot 3.5.16, native PostgreSQL 17.4, Maven 3.9.11, Node 24.18.0, React 19.3.1, TypeScript 7.0.2, Vite 8.3.5, and Playwright 1.64.1. Frontend versions are pinned in package.json/package-lock.json; Spring dependencies use the Boot BOM.

The implementation deliberately uses Spring JDBC instead of the blueprint's proposed JPA and the installed PostgreSQL 17 instead of PostgreSQL 18. See the architecture decision record for the reasons.

Docker, other operating systems, external payment networks, live customer data, cloud failover, regulatory controls, and public deployment were not validated. Scheduled transfers are the chosen Phase 10 extension; FX, holds, fees, interest, partial reversals, and cross-database transfers remain outside the release.
