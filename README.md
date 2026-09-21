# AI-Native Search Ranking Optimization Platform

A Java/Spring platform for developing search-ranking data and services. The
current application provides a PostgreSQL-backed ad catalog. Separate Java/Spark
reference implementations define selected analytics and feature semantics.

## Current scope

| Component | Status |
| --- | --- |
| Java 21 / Spring Boot application | Catalog API with validated create/read operations |
| PostgreSQL persistence | Explicit JDBC queries, UUID identifiers, UTC timestamps, Flyway migration |
| Operational behavior | Health, liveness/readiness, bounded DB waits, Problem Details errors |
| Verification | Unit tests, real HTTP/PostgreSQL integration tests, smoke script, CI workflow |
| Java / Spark references | Ten implementations with synthetic fixtures and named checks |
| Ranking, feature publication, models, experiments | Subsequent platform milestones |

The application foundation passes its **JDK 21 build, eight unit tests, and
21 PostgreSQL integration checks in CI**. The manual Compose startup, smoke,
and restart/recovery exercises remain pending. See
[verification evidence](docs/verification.md) for the tested commit and run.
No production throughput, ranking quality, or business lift has been established.

## Start here

Install JDK 21 and Docker with Compose. Copy `.env.example` to `.env` and choose
a local database password. From the repository root:

```bash
set -a
source .env
set +a
docker compose up -d --wait postgres
./mvnw spring-boot:run
```

The application listens on `127.0.0.1:8080`. In another terminal:

```bash
curl -i http://127.0.0.1:8080/actuator/health/readiness
curl -i http://127.0.0.1:8080/api/v1/ads \
  -H 'Content-Type: application/json' \
  -d '{"title":"Camera","category":"Electronics"}'
```

Creation returns `201` with the ad and its relative `Location` URI. Fetch that
URI to read the persisted record. Creation is not idempotent; see the
[API contract](docs/api.md) before implementing client retries.

## Verify

Run unit tests or the complete verification gate:

```bash
./mvnw test
./mvnw -Pintegration verify
```

Integration tests require Docker and create disposable PostgreSQL instances;
they do not use your development database. The CI workflow runs the full gate.
For an already-running local application, `bash scripts/smoke-test.sh` performs
a create/read/validation check using `curl` and `jq`.

## Documentation

| Document | Purpose |
| --- | --- |
| [Development guide](docs/development.md) | Setup, configuration, test commands, troubleshooting |
| [API contract](docs/api.md) / [OpenAPI](docs/openapi.yaml) | Requests, responses, errors, identity, retry semantics |
| [Architecture](docs/architecture.md) | Implemented module boundaries and future platform topology |
| [Foundation decision](docs/decisions/0001-application-foundation.md) | Alternatives, trade-offs, and consequences |
| [Operations](docs/operations.md) | Startup, database outages, health, persistence, recovery |
| [Verification evidence](docs/verification.md) | Checks performed and remaining gates |

## Analytics references

The standalone [Java/Spark references](practice/spark/README.md) cover CTR, lookup
joins, ranking windows, event versions, rolling metrics, skew, aggregation,
experiments, point-in-time features, and Parquet behavior. Their runtime stays
outside the HTTP application's classpath.

```bash
export SPARK_PREP_HOME=/absolute/path/to/spark-4.0.1-bin-hadoop3
bash practice/spark/run.sh --lab all
```

The [CTR exercise](docs/exercises/01-clicked-impression-ctr.md) provides an optional
implementation exercise alongside the working references. These references are
not yet integrated with catalog data.

## Build the platform in stages

The [learning path](docs/learning-path.md) gives each stage a deliverable, an
acceptance check, and questions to explain aloud:

1. Event contracts, deduplication, and CTR.
2. Batch features, rolling windows, and point-in-time joins.
3. A deterministic Java search and ranking baseline.
4. A first trained model with temporal evaluation.
5. Randomized assignment and trustworthy experiment metrics.
6. Versioned deployment, fallback, rollback, and monitoring.

See the [target architecture](docs/architecture.md) for how those pieces fit.
Build one stage at a time and record what the evidence actually shows.

## Primary references

- [Apache Spark 4.0.1 overview and supported runtimes](https://spark.apache.org/docs/4.0.1/)
- [Java Dataset API](https://spark.apache.org/docs/4.0.1/api/java/org/apache/spark/sql/Dataset.html)
- [Spark SQL performance tuning](https://spark.apache.org/docs/4.0.1/sql-performance-tuning.html)
- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
