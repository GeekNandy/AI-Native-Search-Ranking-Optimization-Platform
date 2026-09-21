# 0001: establish an explicit catalog and persistence boundary

Status: implemented; full integration verification pending.

## Context

The platform needs a small, durable HTTP feature before adding event ingestion
and ranking. The initial repository has a Spring scaffold and standalone Spark
references. We need to establish API semantics, schema ownership, and an
observable failure path without introducing distributed coordination.

## Decision

Use one Spring Boot application organized by feature. The catalog has a plain
Java domain record, a framework-independent application service, a repository
port, a JDBC adapter, and HTTP request/response types. Spring configuration wires
the application service to the adapter and a UTC clock.

Use PostgreSQL and explicit parameterized SQL through Spring JDBC. Flyway is the
sole schema initialization mechanism. Start with create/read operations and the
primary-key access path they actually require. Each operation has one SQL
statement, so no additional transaction annotation is needed at this stage.

Use separate request/response types so persistence and domain changes do not
silently alter the public API. Validate incoming fields and preserve domain
invariants for non-HTTP callers. Database constraints provide a further boundary
for direct writers. Text length is measured in Unicode code points, matching
PostgreSQL character lengths; the HTTP adapter uses Hibernate Validator's
`CodePointLength` constraint for the same semantics.

Creation uses server-generated UUIDs and an injected clock. Precision is
normalized to PostgreSQL's microseconds before persistence. Duplicate payloads
are permitted; creation is explicitly non-idempotent until a durable retry
contract is designed.

Keep Spark in a separate batch runner. The HTTP application neither embeds Spark
nor launches a job for each request.

## Alternatives and consequences

| Alternative | Trade-off behind this choice |
| --- | --- |
| ORM/JPA | Useful for larger entity models; explicit SQL keeps these two queries and their data behavior easy to inspect |
| Embedded test database | Faster startup, but does not establish PostgreSQL migration, UUID, timestamp, and constraint behavior |
| Separate catalog microservice | Adds remote failure and deployment concerns before an independent scaling or ownership requirement exists |
| Generic repository/service framework | Adds abstraction beyond the two current use cases; the repository port stays specific to the catalog |
| In-memory idempotency map | Loses guarantees on restart and across instances; not used to imply durable exactly-once behavior |

The repository port is a deliberate boundary, not a promise to support arbitrary
databases. Moving to multiple writes in one use case will require an explicit
transaction decision. Adding a controlled category taxonomy will require a new
contract and migration. The current defaults support local development and need
operational review before deployment.

## Evidence and follow-up

Unit tests cover domain boundaries and use-case behavior. Integration tests
cover real HTTP, migrations, JDBC, SQL binding, constraints, validation, and DB
outage responses. The supplied CI workflow runs both levels. Full integration
execution is a required remaining gate because the current implementation
environment cannot resolve Maven Central and has no Docker/JDK 21 runtime.
