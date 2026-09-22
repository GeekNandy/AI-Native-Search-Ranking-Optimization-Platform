# Ranking and ML verification

This report covers the `search-ranking-ml` increment. The previous catalog
foundation's evidence remains in [verification.md](verification.md).

## Executed Spark run

Apache Spark 4.0.1 on OpenJDK 17 executed the checked-in Java pipeline against
6,400 seeded synthetic examples, with cutoff `2026-09-21T12:00:00Z`.

| Check | Observed result |
| --- | --- |
| Training / validation / test | 3,184 / 1,352 / 912 rows |
| Purged label windows | 952 rows |
| Test log loss | 0.555017; constant-prior baseline 0.682907 |
| Test Brier score | 0.187827 |
| Test AUC | 0.781585 |
| Model / baseline NDCG@10 | 0.874810 / 0.840448 |
| Maximum Java/Spark probability difference | `2.220446049250313e-16` (gate: `1e-9`) |
| Output | Canonical and daily Parquet, model/preprocessing, feature snapshot, evaluation and completion manifest |

These are synthetic software checks, not measurements of production relevance,
revenue, latency capacity or causal lift. The generated data deliberately contains
a learnable signal.

## Application verification

The increment adds unit tests for scoring, schema/vector constraints, cold start,
assignment and uncertainty, plus HTTP/PostgreSQL integration scenarios for
events, exports, features, models, experiments and administrative access.

Java 21 compilation and all 16 unit tests pass locally (zero failures, errors or
skips). PostgreSQL integration and container demonstration results are pending
while this change is being completed. The local environment has no Docker. The CI
workflow is configured to run `./mvnw -Pintegration verify`, container
startup/recovery checks, and the trained-model demonstration. Replace this
pending status with observed results before merging.

## Commands

```bash
./mvnw test
./mvnw -Pintegration verify
docker compose --profile application up --build -d
bash scripts/verify-runtime.sh
bash scripts/demo.sh
```

The runtime script stops/restarts PostgreSQL; run it only in a disposable Compose
project. Passing these checks does not replace a production load test or a
backup/restore exercise.
