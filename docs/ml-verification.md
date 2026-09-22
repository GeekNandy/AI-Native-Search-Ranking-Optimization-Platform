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

## Application and container verification

[GitHub Actions run 35694561257](https://github.com/GeekNandy/AI-Native-Search-Ranking-Optimization-Platform/actions/runs/35694561257)
passed on September 22, 2026 for implementation commit `7baa8af7137de71f6ac398f7a5dd24205abe4f13`.
The runner used Java 21, PostgreSQL 17 containers and Spark 4.0.1.

| Check | Observed result |
| --- | --- |
| Java compilation and unit tests | 16 passed; zero failures, errors or skips |
| HTTP/PostgreSQL integration tests | 29 passed; zero failures, errors or skips |
| Container image | Built successfully; application started with both Flyway migrations |
| Database outage | Readiness and catalog lookup returned `503`; liveness remained `200` |
| Database recovery | Readiness returned `200`; the previously created ad retained identical content |
| HTTP smoke check | Creation, lookup and invalid-input rejection passed |
| Complete ML demonstration | Train, evaluate, publish, select model, rank, expose and click passed |
| Serving assertion | Response used the newly published model and snapshot, with five probability-scored results and no fallback |
| CI scoring parity | Maximum difference `2.220446049250313e-16`; test log loss `0.5550168101063657` |

Integration scenarios cover duplicate/conflicting events, attribution bounds,
label maturity, immutable exports, feature publication rollback, schema and
quality gates, concurrent deployment changes, model rollback, stale-feature
fallback, experimental non-converters, experiment stopping and token protection
for both administrative routes and Prometheus.

Local Java 21 compilation and the same 16 unit tests also passed. The initial
standalone Spark run above used Java 17; the container demonstration in CI used
Java 21. No production traffic, capacity benchmark or causal experiment was run.

This is a dated execution record. Use the checks on
[draft PR #4](https://github.com/GeekNandy/AI-Native-Search-Ranking-Optimization-Platform/pull/4)
for verification of subsequent commits on the branch.

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
