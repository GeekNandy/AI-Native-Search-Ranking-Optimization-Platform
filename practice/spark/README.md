# Java / Spark practice

Ten reference labs connect SQL and Java Spark code to search-ranking data problems.
Lab 1 also has a separate learner starter. No JUnit is needed for these labs;
the existing Spring application retains its own Maven test setup.

## Prerequisites

- A JDK. Spark 4.0.1 supports Java 17 and 21; the local verification used Java 17.
  Use JDK 21 when working on the root Spring application.
- An extracted Apache Spark 4.0.1 distribution built for Scala 2.13, such as the
  Hadoop 3 binary distribution from the [official Spark downloads](https://spark.apache.org/downloads.html)
  or [4.0.1 archive](https://archive.apache.org/dist/spark/spark-4.0.1/).
- Bash, available on Linux, macOS, or WSL.

No Hadoop cluster, Maven resolution of Spark dependencies, or Python program is
needed. This runner uses the JARs and `spark-submit` in your installed Spark
distribution. See the [version-specific requirements](https://spark.apache.org/docs/4.0.1/).

## Run from the repository root

```bash
export SPARK_PREP_HOME=/absolute/path/to/spark-4.0.1-bin-hadoop3
java -version
bash practice/spark/run.sh --lab 1
bash practice/spark/run.sh --lab all
```

`SPARK_HOME` is also accepted if `SPARK_PREP_HOME` is unset. The runner compiles
with `--release 17`, creates a local JAR, and uses two local Spark execution
threads. Compilation falls back to JDK modules if the `javac` and `jar` launchers
are unavailable. A full JDK installation is the easiest setup.

Outputs go to `practice/spark/my_results/`: a verification summary and selected
formatted execution plans. These generated files and compiled classes are
ignored by Git. `--output-dir` accepts an absolute path; a relative path is
resolved inside `practice/spark/`, because the runner changes into that directory.

## Work the first problem yourself

Read the [CTR problem](../../docs/exercises/01-clicked-impression-ctr.md), edit
[Lab01Ctr.java](Lab01Ctr.java), then run:

```bash
bash practice/spark/run.sh --learner --lab 1 --output-dir my_results/learner
```

An untouched starter raises `UnsupportedOperationException`. That is intentional.
The learner mode checks your method against expected rows for duplicate delivery,
multiple clicks on one impression, attribution boundaries, late arrival, orphan
clicks, and zero-click ads. A passing result prints `S01 PASS`.

Only Lab 1 currently has a separate starter. For Labs 2–10, read the named check
and fixtures first, then reimplement the corresponding `sXX` method in your own
working branch before comparing with its reference. `--learner` accepts Lab 1
only; without a specific lab, it selects Lab 1.

## Reference lab map

| Lab | Implemented concept | Main check |
| --- | --- | --- |
| 1 | Clicked-impression CTR in Java and SQL | Correct cohort, time boundaries, and denominator |
| 2 | Small lookup table and broadcast join | Preserve facts; reject duplicate and null lookup keys |
| 3 | Top three ads per category | Volume threshold before ranking; deterministic ties |
| 4 | Latest observed event version | Receipt cutoff, tie-breaker, and tombstone semantics |
| 5 | Seven calendar-day CTR | Fill missing dates; aggregate counts before dividing |
| 6 | Selective salting of a hot join key | Same output multiset and totals as unsalted join |
| 7 | Dataset and Java RDD aggregation | Pool counts; preserve results across partition counts |
| 8 | Assigned-user conversion rate | Keep non-converters and require a complete follow-up window |
| 9 | Point-in-time feature lookup | Exclude future or unavailable features; preserve missing history |
| 10 | Parquet scan, partition pruning, cache reuse | Preserve data through scan and partition operations |

Reference implementations, fixtures, SQL, and named checks live in
[SparkInterviewPractice.java](SparkInterviewPractice.java). The checks throw on
failure; they do not depend on Java's `-ea` flag. Driver collection is used only
to inspect these deliberately tiny fixtures.

## Explain the execution, not just the answer

After a passing run, inspect the saved plans and explain:

- Why a broadcast lookup can avoid shuffling the large side, and what must fit
  in each executor's memory.
- Why grouping and window operations can require exchanges and sorts.
- Why the salting example replicates only the lookup row for the hot key, and
  why its merge hints demonstrate an approach rather than recommend it generally.
- Why partial aggregation can reduce data crossing the network.
- How partition filters differ from filtering rows after a scan.
- Why caching is useful only when reuse outweighs materialization and storage cost.

Adaptive execution and configuration can change physical plans. A plan from
small local fixtures is not a production benchmark. Discuss skew, shuffle bytes,
spill, task-duration spread, and partition sizes before proposing a tuning change.
