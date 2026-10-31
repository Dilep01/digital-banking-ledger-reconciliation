# Local operations

## Processes and ports

- Packaged banking application: `127.0.0.1:8080`.
- Optional React development server: `127.0.0.1:5178`, with a proxy to the backend.
- Project PostgreSQL cluster: `127.0.0.1:55438`.
- Temporary two-instance verification: `8081` and `8082` with an isolated `ledger_cluster_<timestamp>` database.

`scripts/start.ps1` defaults to demo mode and localhost binding. `-NoDemo` skips seeding; customers can register, but staff provisioning then requires a controlled local database administration step. The demo identities already present are not removed by changing this flag.

`.env` is loaded by the PowerShell scripts as key/value data, not executed as code. Existing cluster initialization credentials are local defaults; changing environment values alone does not rotate database role credentials. Direct Java invocation reads environment variables rather than automatically parsing .env.

## Logs and correlation

Runtime logs live under `.runtime`. Every HTTP request receives `X-Correlation-ID`; service logs and audit records use that identifier. Failure responses avoid exposing SQL and credentials. Background failures record their event/job identifier in server logs and retain durable attempt state.

GET `/actuator/health` is public with minimal details. Actuator info/metrics require the auditor role. Staff can inspect outbox state in Background activity. There is no external monitoring subscription.

## Worker behavior

The worker polls every two seconds by default and processes at most ten scheduling/event pairs per tick. Work is claimed with row locks and SKIP LOCKED. Failed processing rolls back its consumer transaction, then records retry information in a separate transaction. Five failed attempts put the item in FAILED state, with a ten-second retry delay between failures. Failed outbox events can be requeued by an operator.

Scheduled transfer creation and execution use different idempotency scopes. Execution uses `scheduled/<schedule UUID>`. Insufficient funds at due time is a terminal REJECTED business outcome, not an automatic promise to transfer later. Funds are never reserved by scheduling. A customer can cancel only while the schedule remains pending.

Event delivery currently creates a local inbox notification. It does not send email/SMS or call another system. The processed-event marker and inbox write commit together; replaying delivery does not duplicate the inbox item.

## Backup and restore

`scripts/backup.ps1` writes a PostgreSQL custom-format dump under `.runtime/backups`. PostgreSQL takes a consistent dump snapshot. Preserve backups separately if the whole workspace is being removed.

`scripts/restore-test.ps1` restores into a new `ledger_restore_<timestamp>` database without overwriting development data. It compares counts of journals/entries/audit events, debit/credit totals, and a sorted balance fingerprint. Run this comparison when no financial writes are in progress, otherwise a later live fingerprint can legitimately differ from the earlier dump.

The restored database is retained for inspection. The verification script prints its exact name. These small validation databases are not public services and can later be removed with PostgreSQL administration tools after confirming their names.

## Database roles

Flyway runs as `ledger_owner`; application queries run as `ledger_app`. Migration credentials should be separated further for a deployment where application operators must not have schema ownership. This local demo deliberately documents its setup defaults.

Do not manually update balances to resolve an exception. Correct supported financial errors through a reviewed full reversal. A projection mismatch requires investigation of the posting path and evidence before an administrator performs any controlled repair.

## Rebuilding

Stop the backend first on Windows, build the frontend, then package Java. A running JVM can prevent Maven from replacing its application file. `scripts/build.ps1` runs both test suites unless `-SkipTests` is explicitly chosen.

The test profile uses only `ledger_test` on the project cluster. Integration tests truncate that dedicated database between cases. Do not point that profile at a database containing data you want to preserve.

## Release boundaries

No public host, TLS certificate, container runtime, broker, SMS service, or live payment endpoint is needed. The checked native setup and packaged JAR are the supported local workflow. Public deployment would require replacing demo credentials, HTTPS/Secure cookies, independent operational controls, and a separate readiness review.
