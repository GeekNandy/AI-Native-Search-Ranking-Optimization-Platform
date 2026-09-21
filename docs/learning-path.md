# Build the platform by learning one boundary at a time

The repository contains an implemented catalog application foundation, ten Spark
reference labs, and a learner starter for the first lab. See the
[development guide](development.md) for the foundation's remaining runtime
verification gate. The stages below describe subsequent platform work. Use Java
and SQL for the data and serving track, and keep each change small enough to
explain and verify on its own.

## Stage 1: event contracts and metrics

**Build:** Finish [the CTR learner exercise](exercises/01-clicked-impression-ctr.md).
Then define Java records for impression and click events and a small Java
collection-based reference calculation. Specify event IDs, timestamps, receipt
times, required fields, version rules, and what happens to invalid records.

**Practise:** Join cardinality, filtering, aggregation, null handling, cohort
definitions, and deduplication. Work through Spark Labs 1, 2, and 4.

**Done when:** Java collections, Spark SQL, and Java Spark agree on the fixture
results. Duplicate deliveries do not change counts. Invalid or conflicting
records have an explicit rejection or quarantine policy. Zero-click impressions
remain in the denominator. Replaying the same canonical snapshot gives the same
report.

**Explain aloud:** “What is one row at each stage? What exactly does CTR mean?
Which timestamps define the cohort, attribution, and reporting cutoff? What
prevents a one-to-many join from inflating the result?”

## Stage 2: batch features and reproducible snapshots

**Build:** Write a Java Spark batch job that reads versioned event fixtures from
Parquet, computes daily and rolling features, and publishes a versioned feature
snapshot. Store source snapshot IDs, reporting cutoff, feature definition
version, event-time coverage, and availability time with the result. Choose
partition columns based on actual reads rather than making every identifier a
directory.

**Practise:** Labs 3, 5, 6, 7, 9, and 10. Check lookup uniqueness, missing dates,
weighted aggregation, skew, shuffle, cache reuse, and point-in-time joins.
Historical training features must have been available at the prediction time.
Same-day totals computed after a prediction cannot be used as past features.

**Done when:** A controlled late event changes only the intended snapshot/cohort.
An incomplete output is never exposed as the current snapshot. Point-in-time
lookups reject future information and preserve rows with missing history. Join
optimization preserves the output multiset, including duplicates that are valid
under the data contract. Explain the observed execution plan before changing it.

**Explain aloud:** “What happens after a partial write or retry? How do we avoid
training on information the online system could not have known? What evidence
would make broadcast, repartitioning, or selective salting appropriate?”

## Stage 3: a deterministic Java ranking baseline

**Build:** Add a small Spring HTTP API with a synthetic catalog and query set.
Retrieve a bounded candidate set, join the chosen feature snapshot, apply a
documented baseline score, and return a deterministic top-k result. Separate
candidate retrieval from ranking. Include request ID, rank position, feature
snapshot ID, policy version, and later model version in the exposure log.

Start with relevance and a simple popularity feature; do not equate raw CTR with
relevance. Choose a deterministic fallback for missing or stale features and a
stable tie-breaker. Add only the web dependency and storage needed for this
stage; a distributed event bus is not a prerequisite for a working local slice.

**Practise:** Java interfaces, bounded work, data contracts, timeouts, and graceful
failure. Explain how an impression relates to one returned result rather than
assuming every returned item was seen by the user.

**Done when:** Known queries return the expected candidate order and deterministic
ties. Missing features and an unavailable feature source trigger a defined
fallback. Logs connect requests, displayed items, positions, and later events.
A measured local latency report states dataset size, concurrency, and hardware.

**Explain aloud:** “What is the scoring objective? What limits request work? What
do you return when the feature source fails? Which exposure events are needed to
evaluate the ranking later?”

## Stage 4: a first model with honest evaluation

**Build:** Use Java Spark ML to fit a small logistic-regression click baseline
with a preprocessing pipeline. Define the prediction unit and label attribution
window. Let labels mature before treating an impression as unclicked. Split
training and validation by time and keep a later untouched test interval.
Fit learned preprocessing only on the training set.

Compare with the Stage 3 baseline. Log loss and calibration assess probability
predictions; a ranking metric such as NDCG must be defined and aggregated by the
appropriate query/request groups. Preserve label and feature versions. Export
the fitted preprocessing and model together, with a compatible serving contract.

**Practise:** Non-learning model diagnosis, leakage, imbalance, overfitting,
constant features, feature order, missing values, and training-serving agreement.
Construct small failing examples: a constant prediction, a future feature, and
a column-order mismatch. Explain what signal would reveal each bug.

**Done when:** Training is reproducible from declared inputs. Validation never
fits the scaler or feature encoder. The same example is transformed and scored
consistently offline and in the proposed serving path within a stated numerical
tolerance. The evaluation compares against a simple baseline and names limitations.

Synthetic labels are useful for checking whether a pipeline learns a known
signal. They cannot establish real-world relevance or business lift. Logged
clicks also reflect position and exposure bias; offline gains do not guarantee
an online improvement.

**Explain aloud:** “Why is this split realistic? Why might a model not learn? What
belongs in the saved artifact? How would I distinguish a model problem from a
data or serving problem?”

## Stage 5: randomized experiments and interpretable metrics

**Build:** Assign eligible users to arms with a stable, versioned hash of the
experiment ID, user ID, and salt. Fix eligibility before treatment and record the
assignment. Keep the user in the same arm across requests. Use an explicitly
defined exposure policy and distinguish assignment from exposure.

Implement Lab 8's assigned-user metric first. Include users without conversions
and restrict the report to users with a mature follow-up window. Then add an
allocation check, confidence intervals appropriate to the metric and
randomization unit, and guardrails such as latency and error rate. A simple
user-level binary conversion metric is a good starting point; a ratio metric
such as CTR needs a variance method that respects dependence within users.

**Practise:** Why randomization reduces systematic confounding, why a before/after
comparison is weak evidence, how sample-ratio mismatch can reveal instrumentation
problems, and why repeatedly peeking at ordinary fixed-horizon p-values can
invalidate a decision rule.

**Done when:** Assignment is stable, arms are exclusive, event retries do not
change counts, non-converters remain present, and identical mature windows are
used in both arms. Synthetic A/A data exercises the analysis code; a tiny
fixture is not a power study. Set a decision rule and stopping plan before
interpreting a real A/B result.

**Explain aloud:** “What is randomized, who enters the denominator, and what is the
analysis unit? Could treatment change inclusion? What would make me distrust an
apparent lift?”

## Stage 6: deployment and monitoring

**Build:** Package model, preprocessing, schema, and feature compatibility metadata
as an immutable version. Validate it before promotion. Define a publication
mechanism that exposes only complete compatible artifacts. Add a limited rollout,
fallback policy, and a tested way to restore the last known good version.

Monitor latency, failures, feature missingness and freshness, score distributions,
and delayed model quality once labels mature. Separate immediate operational
signals from quality signals with attribution delay. Preserve version information
so that a regression can be linked to a particular change.

**Practise:** Integration tests across training and serving, feature-service
timeouts, incompatible schemas, partial batch outputs, retry idempotency, and
rollback. Explain the consistency guarantees of the chosen storage/publication
mechanism instead of assuming all storage supports an atomic directory rename.

**Done when:** An incompatible artifact cannot be promoted. An injected feature
failure exercises the expected fallback. A failed rollout can restore a known
version without mixing preprocessing and model versions. Reprocessing a batch
does not create duplicate published results.

**Explain aloud:** “What fails first? What will alert me? Can I serve a safe
baseline? How do I verify that rollback restored the intended model and features?”

## Keep a short evidence note after each stage

Record five things in your own words:

1. The problem and exact metric or contract.
2. The smallest input that exposes a bug.
3. One design tradeoff and why you chose it.
4. What you ran, what happened, and the limits of that evidence.
5. One failure path and how the system recovers.

This makes the repository useful for an interview: every architectural claim
can be connected to code, a concrete example, or a clearly identified future step.
