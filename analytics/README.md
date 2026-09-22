# Java/Spark ranking pipeline

`RankingPipeline` uses Apache Spark 4.0.1 to validate impression examples, write
Parquet features, fit/evaluate logistic regression, and export a data-only Java
artifact. Spark libraries stay outside the Spring application.

```bash
export SPARK_HOME=/absolute/path/to/spark-4.0.1-bin-hadoop3
bash analytics/run.sh --synthetic \
  --output analytics/output/example \
  --as-of 2026-09-21T12:00:00Z \
  --version example-v1 \
  --snapshot-id 1e0c3e09-2f71-4dac-ab9e-a50ca1fc8555
```

Use Java 17 or 21. The runner compiles against the distribution's jars and calls
`spark-submit`. CI obtains this distribution from its pinned PySpark package;
application code remains Java.

For observed data, replace `--synthetic` with `--input FILE.jsonl` and provide
`--train-end`, `--validation-end`, `--as-of` and optionally `--window-start`.
Synthetic mode accepts `--catalog FILE.jsonl` containing local catalog-create
responses so feature snapshots can use local ad IDs.

Output directories are immutable. Failed runs leave a staging directory without
publishing the destination. `publish-run.sh` requires a completion manifest and
matching hashes. See [ML workflow](../docs/ml-workflow.md),
[data contracts](../docs/data-contracts.md) and [verification](../docs/ml-verification.md).
