# Application foundation: operations

## Startup and schema ownership

The application requires PostgreSQL. Flyway completes schema migration before
the application is ready. An unavailable database or invalid migration prevents
successful startup; it should not be masked with an empty in-memory database.

The catalog owns the `ads` table. Use forward migrations for schema changes.
Flyway clean is disabled in application configuration. PostgreSQL credentials in
Compose are initialized only when its data directory is empty.

## Health behavior

Use `/actuator/health/readiness` to determine whether the instance can serve
database-backed requests. Use `/actuator/health/liveness` to assess the process.
The database is intentionally excluded from liveness to avoid restarting every
application instance during a shared database outage.

Readiness proves connectivity, not the correctness of every query or a guarantee
that the next request succeeds. The integration tests separately exercise the
actual schema and request path.

## Bounds and failure responses

Local defaults are five pooled connections, a two-second pool acquisition
timeout, a one-second connection validation timeout, a three-second driver
connect timeout, and five-second socket and statement timeouts. These are initial
operational bounds, not measured latency targets or a strict end-to-end SLA.
Tune pool capacity against instance count, database limits, and observed demand.

Database connection/resource failures and query timeouts map to `503`. Unexpected
errors map to `500` with a generic response. Internal exceptions go to server
logs; bodies and database credentials are not included in API error responses.
Database access can recover after PostgreSQL returns; the pool replaces failed
connections. Verify recovery rather than assuming every connection is healthy.

No automatic write retry is enabled. A database can commit a write before a
connection fails, leaving the client unable to determine the outcome. The API's
non-idempotent creation contract makes this distinction explicit.

## Manual persistence and recovery check

1. Create an ad and retain its UUID and response.
2. Stop and restart the application. Fetch the UUID and compare the response.
3. Restart PostgreSQL without deleting its volume. Fetch the UUID again.
4. While the application is running, stop PostgreSQL and check readiness and
   an ad lookup. Expect `503`; check liveness separately and expect `200`.
5. Start PostgreSQL and wait for readiness to recover. Read the same UUID again.

These steps are a review checklist, not a claim that a recovery exercise has
already been completed in this environment. `DatabaseAvailabilityIT` automates
the outage classification against its own disposable database.

## Delivery boundary

The supplied GitHub workflow verifies the application using JDK 21 and a real
PostgreSQL container. It does not deploy anything. Review the full integration
result before merging. Database backup/restore, authentication, deployment
topology, secret delivery, and SLOs remain work for an externally hosted service.
