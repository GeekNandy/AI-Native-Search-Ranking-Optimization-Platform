# Roadmap coverage

| Original stage | Delivered | Acceptance checks |
| --- | --- | --- |
| Events and metrics | Exposure/click APIs, uniqueness, conflicts and mature CTR | Retries, multiple clicks, invalid attribution and maturity |
| Batch features | Frozen exports, canonical/daily Parquet, rolling snapshots and atomic publication | Spark execution, snapshot rollback and keyset paging |
| Ranking baseline | Full-text retrieval, bounded work, deterministic score/ties and recorded inputs | Ordering, input bounds, cold start and stale-feature fallback |
| Trained model | Scaling/logistic regression, purged temporal splits, validation selection and export | Synthetic training, distributed scoring parity, schema and quality gates |
| Experiments | Persistent buckets, pinned treatment, non-converter denominator and stop | Assignment/report integration and statistical boundaries |
| Deployment/monitoring | Immutable registry, revision-checked rollout/rollback, metrics and runtime scripts | Concurrent promotion, rollback, administrative access and recovery |

[Verification evidence](ml-verification.md) records which checks ran. A test
implementation or configured workflow does not by itself establish a pass.

## Operating requirements beyond this increment

External hosting needs identity/RBAC, TLS, secrets, quotas, retention/deletion,
backup/restore and capacity/SLO measurements. Larger datasets may need object
storage, partitioned events and incremental processing.

Real model development needs relevance judgments/business outcomes, exposure
bias analysis, drift thresholds and powered experiments. Neural retrieval,
learning-to-rank losses and exploration are extensions whose value is not
established by synthetic fixtures.
