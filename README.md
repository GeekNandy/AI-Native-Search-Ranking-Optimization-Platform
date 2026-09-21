# AI-Native Search Ranking Optimization Platform

A Java-first learning project for search ranking: turn event data into reliable
metrics, build features, serve a ranking baseline, and then add model evaluation
and experiments. SQL and Apache Spark are the first practice track.

## What works today

| Component | Status |
| --- | --- |
| Java 21 / Spring Boot application | Generated application and context-test scaffold |
| Java / Spark practice | Ten runnable reference labs with synthetic fixtures and named checks |
| First learner exercise | CTR problem, sample data, expected results, and a starter method to implement |
| Search API, feature publication, trained model, experiment service | Planned in the learning path; not implemented yet |

These exercises check data correctness on small inputs. They do not establish
production throughput, ranking quality, or business lift.

## Start here

1. Read [Exercise 1: clicked-impression CTR](docs/exercises/01-clicked-impression-ctr.md).
2. Follow the [Spark setup instructions](practice/spark/README.md).
3. Implement `compute` in [Lab01Ctr.java](practice/spark/Lab01Ctr.java).
4. From the repository root, run:

```bash
export SPARK_PREP_HOME=/absolute/path/to/spark-4.0.1-bin-hadoop3
bash practice/spark/run.sh --learner --lab 1
```

The learner starter intentionally throws until you implement it. To inspect the
working reference, use `--lab 1` without `--learner`. To check all ten references:

```bash
bash practice/spark/run.sh --lab all
```

The runner compiles Java and calls `spark-submit`; it does not run Python code.
Its Spark dependencies stay separate from the Spring application.

## Run the Spring application

Install JDK 21 and use the checked-in Maven wrapper:

```bash
./mvnw test
./mvnw spring-boot:run
```

The current application uses `spring-boot-starter` and has no HTTP server or
endpoint yet. Stage 3 adds a small search API. Maven needs access to its dependency
repositories on first use.

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
