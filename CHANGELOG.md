# Changelog

## v2.0 design + v1.0 executable spine

Added:

- Java ledger domain core
- double-entry ledger transaction validation
- fund reservation flow
- reversal flow baseline
- transaction state machine
- idempotency guard with request hash conflict detection
- explainable fraud engine
- high-risk hold workflow
- fraud case creation
- admin approve/reject flow
- audit log model
- outbox event model
- reconciliation issue model
- domain acceptance validation runner
- Spring Boot Maven project skeleton
- Docker Compose infrastructure
- Next.js operations cockpit scaffold
- v2.0 design docs
- data model SQL
- transaction simulator

Validated locally:

- Java domain compile via `javac`
- domain acceptance scenario
- repository file validation

Not validated in this environment:

- Maven dependency build, because `mvn` is not installed
- Docker Compose startup
- npm/Next.js production build
