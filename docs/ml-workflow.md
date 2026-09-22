# Training, evaluation and serving

## Collect and freeze observed interactions

Create ads, search, and record actual exposure/click events. The target is a click
within 24 hours of exposure; labels mature after 25 hours. Use opaque user IDs.
See [data contracts](data-contracts.md) for time and identity rules.

```bash
bash scripts/export-training.sh 2026-09-21T00:00:00Z analytics/output/events.jsonl
```

Choose a cutoff appropriate to your data. Existing output files are not
overwritten. Retain the printed export UUID and source file with the run.

## Train in Java/Spark

```bash
export SPARK_HOME=/absolute/path/to/spark-4.0.1-bin-hadoop3
bash analytics/run.sh \
  --input analytics/output/events.jsonl \
  --output analytics/output/ctr-v1 \
  --train-end 2026-09-09T00:00:00Z \
  --validation-end 2026-09-15T00:00:00Z \
  --as-of 2026-09-21T00:00:00Z \
  --window-start 2026-08-22T00:00:00Z \
  --version ctr-v1 \
  --snapshot-id 86721d3a-7a93-49d2-91f2-5b2b6fbe0411
```

Use new version/snapshot/directory identities for different runs. Each split
requires at least 20 mature examples and both label classes. This is an execution
sanity check, not a statistical adequacy claim.

Splits are chronological by prediction time. Training labels must mature by
`train-end`, validation labels by `validation-end`, and test labels by `as-of`.
Rows crossing those observation boundaries are purged and counted. Each request
has one prediction time, so request groups cannot span splits.

The vector assembler, mean/std scaler and L2 logistic regression fit on training
data only. Regularization values `0.01` and `0.1` are compared on validation log
loss. The untouched test interval is evaluated after selection. Zero-variance
dimensions export with zero coefficient, matching Spark's scaling behavior.

## Inspect evaluation and artifacts

| Output | Contents |
| --- | --- |
| `canonical.parquet/` | Validated examples |
| `daily-features.parquet/` | Per-day/ad mature counts, partitioned by event date |
| `features.json` | Aggregate snapshot, window, cutoff, schema and source hash |
| `spark-model/` | Native fitted Spark preprocessing/model |
| `model.json` | Feature order, means/scales, coefficients, intercept, split cutoffs, sample sizes and metrics |
| `evaluation.json` | Purged/split counts, trials, test metrics, calibration and comparisons |
| `complete.json` | Model/snapshot hashes and completed-run provenance |

Log loss and Brier score assess probability quality; AUC assesses discrimination.
Calibration bins compare predicted and observed click rates. NDCG@10 groups
observed impressions by request, excludes groups without clicks and compares
against the relevance/popularity baseline. The probability baseline uses the
training click rate; a baseline ranking score is not a click probability.

The Java scorer is checked on every test row using a distributed Spark aggregate;
maximum probability error must be at most `1e-9`. Synthetic runs must also beat
the constant-prior baseline on validation/test log loss. Real runs report poor
models honestly: registration is allowed, but promotion and experiment creation
reject validation log loss worse than the prior baseline.

## Publish and roll out

```bash
bash scripts/publish-run.sh analytics/output/ctr-v1
bash scripts/promote-model.sh ctr-v1 5
bash scripts/promote-model.sh ctr-v1 25
bash scripts/promote-model.sh ctr-v1 100
```

Publication checks completed-run hashes. Model and feature import are separate
idempotent transactions. Registering a model does not select it for serving;
publishing a complete fresh feature snapshot makes that snapshot eligible.
Importing historical data does not reset its freshness cutoff.

Rollout hashes model version and opaque user ID. Increasing the percentage for
the same version retains earlier buckets. Promotion supplies the current
deployment revision; concurrent updates cause `409`. Rollback selects an older
immutable version through the same operation. See [operations](operations.md)
for baseline rollback and separately pinned experiment shutdown.

## Monitor and interpret

Monitor latency/errors, policy/fallback counts, missing history, snapshot age and
score distributions. After label maturity, `/api/v1/admin/metrics/model-quality`
returns log loss, Brier score and CTR by served model version. Traffic mix can
change these metrics without a model bug.

Clicks contain position and exposure bias. This baseline has no propensity
correction, purchase-value objective, exploration policy or causal guarantee.
Offline gains justify a planned experiment, not a business claim. Synthetic
results establish software behavior and recovery of an intentionally generated
signal, not relevance or commercial impact.
