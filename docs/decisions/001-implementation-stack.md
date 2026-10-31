# Implementation decisions

28 October 2026

## Spring Boot 3.5.16 and Java 21

The blueprint proposed Boot 4.1.x pending setup verification. The implementation pins stable Boot 3.5.16 and Java 21. This keeps Spring Security, Spring Session JDBC, Jackson 2, and the test stack on a compatible managed dependency line. It is a deliberate version change, not a claim that Boot 4 cannot implement these features.

The installed native PostgreSQL 17 was used rather than installing PostgreSQL 18. SQL migrations and real-database tests ran on PostgreSQL 17.4. An isolated cluster avoids modifying unrelated projects or existing database services.

## Explicit Spring JDBC for financial persistence

The blueprint suggested JPA. This implementation uses Spring JDBC throughout so conflict-safe inserts, row locks, journal writes, and snapshot queries are explicit. Spring transactions still manage a single connection/transaction for the whole operation. There is no Hibernate entity lifecycle or separate persistence context to reason about.

## Modular monolith

The ledger owns monetary posting in one PostgreSQL transaction. The distributed extension runs two instances of the same application and shared workers. The system does not distribute debit and credit across databases.

## Sessions and CSRF

Spring Security form login establishes an HttpOnly SameSite cookie. PostgreSQL-backed Spring Session supports both instances and invalidates sessions on logout. A server-stored CSRF token is returned through a dedicated endpoint; the UI refreshes it after authentication changes. The packaged UI and API share an origin.

## Ledger locking and immutability

Posting locks account rows in sorted UUID order. Balance writers and account-state writers use those locks. Reversal approval also locks the original transfer metadata row. It does not lock an immutable journal row with FOR UPDATE, because that PostgreSQL operation requires update permission that the application intentionally lacks.

Constraint triggers reject empty or unbalanced journals at commit. UPDATE/DELETE privileges are revoked on evidence tables and defensive triggers cover journals, entries, and audit events. Owners remain privileged; this is not cryptographic tamper resistance.

## Durable work

Outbox and notification deduplication writes share a local transaction. Delivery is designed for replay, not a universal exactly-once guarantee. Scheduled transfers are persistent rows, claimed with SKIP LOCKED, and invoke the same idempotent transfer path. Retries preserve request identity.

## Reconciliation boundary

Statement comparison covers transfer and reversal legs, not opening-funding entries. Each run materializes its internal selection and preserves input evidence. The operator chooses a finalized batch interval; wall-clock time or a maximum sequence ID is not represented as a global commit watermark.

The internal integrity scan checks the complete current ledger, even though a run records an interval for consistency of the API. Synthetic expected amounts in tests and generated demo statements are defined before posting and are not copied from ledger queries.

## Frontend and packaging

React, TypeScript, Vite, TanStack Query, Zod, and plain CSS form the UI. Simple workspace navigation uses component state; a router and form framework were unnecessary for these screens. Amount formatting uses integer arithmetic for decimal-string totals. Exact dependency versions are in package.json and package-lock.json.

The compiled frontend is copied into the Spring Boot JAR during prepare-package. Native Windows scripts avoid command-shell interpretation of the ampersand in the user's project path.
