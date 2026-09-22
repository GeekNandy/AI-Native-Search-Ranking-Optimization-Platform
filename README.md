# AI-Native Search Ranking Optimization Platform

A Java/Spring and Apache Spark platform connecting catalog search, observed
interactions, reproducible training data, and versioned ML serving. PostgreSQL
owns online state; a separate Java/Spark process computes features and trains a
logistic-regression click model. The request path never starts Spark.

## Capabilities

| Area | Implemented behavior |
| --- | --- |
| Retrieval and ranking | Full-text index; 200-candidate cap; relevance/popularity baseline; Java model scoring; stable ties |
| Interaction data | Actual exposures, click attribution, retry deduplication and conflict detection |
| Training data | Immutable exports, keyset pagination, captured prediction-time features and mature labels |
| Batch processing | Canonical Parquet, daily aggregates, rolling feature snapshots and completed-run manifests |
| ML | Training-only scaling, regularization selection, purged temporal splits and untouched test evaluation |
| Evaluation | Log loss, Brier score, AUC, calibration and grouped NDCG with declared baselines |
| Model operations | Immutable registry, validation gate, deterministic rollout and revision-checked rollback |
| Experiments | Durable user assignment, intent-to-treat conversion, uncertainty, allocation checks and stop control |
| Operations | Baseline fallback, delayed quality reports, Prometheus, readiness/liveness and container packaging |

The implementation bounds feature publication to 10,000 ads, frozen exports to
100,000 examples, and responses to 50 results. These are explicit limits, not
production capacity claims. See [architecture](docs/architecture.md) and
[verification evidence](docs/ml-verification.md).

## Run locally

Install Docker with Compose. Copy `.env.example` to `.env`, choose a local
database password, and set `ADMIN_TOKEN` to at least 32 characters (generate one
with `openssl rand -hex 32`). Administrative APIs are disabled without a token.

```bash
docker compose --profile application up --build -d
curl http://127.0.0.1:8080/actuator/health/readiness
```

Application and database ports publish only to loopback. For IDE development
with JDK 21, start only PostgreSQL and run `./mvnw spring-boot:run` after exporting
`.env`. See the [development guide](docs/development.md).

```bash
curl http://127.0.0.1:8080/api/v1/ads \
  -H 'Content-Type: application/json' \
  -d '{"title":"Camera Alpha","category":"Cameras"}'
curl http://127.0.0.1:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"camera","userId":"example-user","limit":10}'
```

Search returns request identity, serving policy, model/snapshot versions and
ranked items. Returning an item does not count as an impression. Catalog
creation and searches are not idempotent; interaction events have explicit
identity and retry contracts.

## Run the complete ML demonstration

Set `SPARK_HOME` to Apache Spark 4.0.1 and use Java 17 or 21 for the batch runner.
The shell scripts also require Bash, `curl`, `jq`, and `sha256sum` (Linux or WSL).
With the application running:

```bash
set -a
source .env
set +a
export SPARK_HOME=/absolute/path/to/spark-4.0.1-bin-hadoop3
bash scripts/demo.sh
```

The script creates a synthetic catalog, trains/evaluates a model, verifies Java
scoring parity, publishes artifacts, enables the model locally and records a
simulated impression/click. Results go under `demo-output/`. It changes local
catalog and deployment state; use a disposable environment.

Synthetic labels test recovery of a known signal. They do not establish real
relevance, business lift or experiment power. For observed interaction data,
follow the [ML workflow](docs/ml-workflow.md).

## Verify

```bash
./mvnw test
./mvnw -Pintegration verify
bash scripts/smoke-test.sh
```

Integration tests require Docker and own disposable PostgreSQL instances. CI
also builds the application container, checks database outage/recovery, and
runs training through model serving. The [verification report](docs/ml-verification.md)
distinguishes executed checks from configured or pending checks.

## Documentation

| Document | Purpose |
| --- | --- |
| [Architecture](docs/architecture.md) | Modules, consistency boundaries and scale limits |
| [API](docs/api.md) / [OpenAPI](docs/openapi.yaml) | Requests, errors, administrative access and retries |
| [Data contracts](docs/data-contracts.md) | Grain, attribution, lateness, maturity and snapshots |
| [ML workflow](docs/ml-workflow.md) | Export, train, evaluate, publish, serve and monitor |
| [Experiments](docs/experiments.md) | Assignment, estimand, uncertainty and limitations |
| [Operations](docs/operations.md) | Rollout, rollback, failures and retention |
| [Development](docs/development.md) | Setup and verification commands |
| [Design decision](docs/decisions/0002-ranking-and-ml.md) | Trade-offs and delivery boundaries |
| [Roadmap coverage](docs/roadmap.md) | Six stages mapped to code and evidence |

Earlier [Java/Spark references](practice/spark/README.md) remain isolated
examples. The integrated batch application is in [`analytics/`](analytics/README.md).
