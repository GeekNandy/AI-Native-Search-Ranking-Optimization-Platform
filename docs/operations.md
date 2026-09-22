# Platform operations

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

No automatic server-side write retry is enabled. A database can commit a write before a
connection fails, leaving the client unable to determine the outcome. The API's
non-idempotent catalog/search contracts make this distinction explicit. Events,
training exports, model registrations and feature publications have explicit
identities: retry the same payload/identity after an uncertain outcome. A `409`
is a payload or revision conflict, not a reason to retry with changed content.

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
result before merging. CI also builds the container and executes a synthetic
training-to-serving demonstration. Database backup/restore, end-user identity,
deployment topology, secret delivery and SLOs remain external-hosting work.

## Promotion and rollback

1. Inspect the immutable model artifact, source hash, split cutoffs and evaluation.
   The server checks schema compatibility and validation loss against the prior
   baseline; this is not a human release decision or an online-benefit guarantee.
2. Register the model and a compatible complete feature snapshot. Importing a
   model alone does not change the active deployment.
3. Use `scripts/promote-model.sh VERSION PERCENT` to read the current revision and
   conditionally update the pointer. Progressively increase traffic only after
   reviewing latency, errors, fallback and mature quality signals.
4. Roll back using the same script with the prior version. A `409` requires
   reading the new deployment state before deciding what to do next.
5. To force the default baseline, `PUT /api/v1/admin/deployment` with the current
   `revision`, `modelVersion: null`, and `rolloutPercent: 0`.

Experiments pin versions independently. Stop affected experiments with
`POST /api/v1/admin/experiments/{id}/stop`; default rollback alone does not change
their treatment. Stopped experiments return baseline results and stop enrolling
users. In-flight requests can finish with previously resolved state.

## Metrics and operational responses

`/actuator/prometheus` requires `X-Admin-Token`, as do administrative APIs. Keep
scraping internal. Never attach user/query/request IDs as metric labels.

| Signal | Interpretation and response |
| --- | --- |
| `platform.search.duration` and HTTP metrics | Check database plans/pool saturation before increasing concurrency |
| `platform.ranking.fallback` by reason | Check snapshot freshness, model presence/schema and experiment stop state |
| `platform.features.age.seconds` / `.missing` | Inspect batch completion, source cutoff and missing ad histories |
| `platform.ranking.score` by policy | Monitor distribution shifts; baseline scores and probabilities have different meanings |
| `platform.events.replays` / `.conflicts` | Distinguish harmless delivery retry from broken identity/payload generation |
| `/admin/metrics/model-quality` | Mature probability loss/Brier/CTR by model; account for changing exposure and traffic |

In-process counters/timers are operational observations, not exactly-once
business denominators: transaction failures after instrumentation and process
restarts can affect them. Reconstruct business metrics from persisted events.
Alert thresholds and latency SLOs require an observed workload baseline.

## Storage and retention

Searches, events, frozen exports, model artifacts and deployment history grow
until explicitly retained/removed. This increment has no automatic deletion
policy. Define retention and privacy obligations before collecting real user data.
Preserve dataset/model provenance needed for an active experiment or rollback.
Use backups and verify restoration before treating PostgreSQL as durable across
host loss; a named local volume is not a backup.

Failed batch runs keep an incomplete staging directory; they do not replace a
completed destination. Retry with new run identities after fixing the cause.
The current local atomic-move contract cannot be assumed for object storage.
