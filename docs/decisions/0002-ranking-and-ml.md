# 0002: connect reproducible training with bounded online ranking

Status: implemented; results are tracked in [verification evidence](../ml-verification.md).

## Problem and decisions

An isolated catalog and analytics functions cannot establish feature availability
at prediction time, prevent duplicated labels, or ensure consistent promotion
and rollback. This increment connects those boundaries using explicit identities.

PostgreSQL owns transactional events, complete feature publication, immutable
training exports, model artifacts and deployment revisions. One Spring service
retains this consistency boundary; Java/Spark fits models separately.

A small logistic-regression model provides inspectable coefficients, inexpensive
training and a portable serving contract. Preprocessing travels with the model;
schema/order mismatches are rejected. The batch runner compiles the same pure
Java scorer and checks it against Spark predictions.

Observed exposures carry captured prediction-time features. Finite attribution
and delivery windows define label maturity. One MVCC snapshot freezes training
rows before pagination. Source/artifact hashes establish run provenance.

Immutable model content is separate from revision-checked serving selection.
Experiments pin treatment versions and expose a stop control. Administrative
APIs use a local token and are disabled by default.

## Alternatives and consequences

| Alternative | Decision and consequence |
| --- | --- |
| Kafka immediately | Database uniqueness/transactions meet current bounded needs; measured replay/fan-out requirements can justify a log |
| Attach current aggregates to historical predictions | Rejected due to leakage; serving stores actual input features |
| Start with a large neural ranker | Logistic regression provides an inspectable, portable first model with useful probability diagnostics |
| Run Spark in HTTP requests | Rejected; request latency and dependency lifecycle remain independent |
| Mutate a model file in place | Rejected; immutable artifacts and atomic selection prevent mixed preprocessing/model state |
| Equate raw CTR with relevance | Query relevance remains in retrieval/baseline; CTR is one behavioral feature |
| Collect all examples to the driver | Fitting, metrics and parity aggregate in Spark; only bounded publication and small reports collect |
| Launch on offline lift | Offline exposure/position bias requires a planned randomized experiment |

## Delivery boundary

The result is a runnable backend/data/ML system with explicit local bounds.
Production traffic, business lift, semantic retrieval, cloud control planes,
organization-wide authorization, autoscaling, deletion enforcement and disaster
recovery are not established. Add those from measured operating requirements.
