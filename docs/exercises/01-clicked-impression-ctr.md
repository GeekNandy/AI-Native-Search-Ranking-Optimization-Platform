# Exercise 1: compute clicked-impression CTR

## Problem statement

Build an ad-level report for impressions shown on **1 September 2026, UTC**.
For this exercise, CTR means the fraction of those impressions that received at
least one valid click. Count a clicked impression once, even if it has multiple
clicks or a click is delivered more than once.

The report includes only data received **before 4 September 2026 00:00:00 UTC**.
A valid click occurs at or after its impression timestamp and strictly before
24 hours after that timestamp. A click can occur on the next calendar day.

Return one row per ad with at least one eligible impression:

```text
ad_id STRING, impressions BIGINT, clicked BIGINT, ctr DOUBLE
```

Keep ads with zero clicks. Do not count orphan clicks, reset attribution at
midnight, round the answer prematurely, or multiply the denominator through a
one-to-many join. `cost_cents` is supplied as a useful conservation check, but is
not part of the required output.

This is a synthetic practice problem, not a claim about a particular company's
interview question or production metric definition.

## Data contract

All timestamps are UTC and required fields in these fixtures are non-null.
Impressions are already canonical: `impression_id` is unique. Repeated deliveries
of a given `click_id` have identical payloads. Conflicting versions and deletions
require a separate canonicalization rule; Lab 4 covers that topic.

```sql
CREATE TABLE impressions (
    impression_id STRING,
    ad_id STRING,
    impression_ts TIMESTAMP,
    received_ts TIMESTAMP,
    cost_cents BIGINT
);

CREATE TABLE clicks (
    click_id STRING,
    impression_id STRING,
    click_ts TIMESTAMP,
    received_ts TIMESTAMP
);
```

These are Spark SQL types. The Java fixture creates the tables in memory, so no
external database is required.

## Input

All abbreviated dates below are in September 2026.

| impression_id | ad_id | impression_ts | received_ts | cost_cents |
| --- | --- | --- | --- | ---: |
| i1 | A1 | 01 10:00 | 01 10:01 | 100 |
| i2 | A1 | 01 11:00 | 01 11:01 | 200 |
| i3 | A2 | 01 23:50 | 02 00:01 | 300 |
| i4 | A3 | 01 12:00 | 01 12:01 | 400 |
| i5 | A1 | 02 10:00 | 02 10:01 | 500 |
| i6 | A1 | 01 15:00 | 04 00:00 | 600 |

| click_id | impression_id | click_ts | received_ts | Reason to notice |
| --- | --- | --- | --- | --- |
| c1 | i1 | 01 10:05 | 01 10:06 | Valid click |
| c1 | i1 | 01 10:05 | 01 10:06 | Identical repeated delivery |
| c2 | i1 | 01 10:10 | 01 10:11 | Another click, same impression |
| c3 | i3 | 02 00:05 | 02 00:06 | Valid click after midnight |
| c4 | i4 | 02 12:00 | 02 12:01 | Exactly 24 hours; excluded |
| c5 | i2 | 01 10:59 | 01 11:01 | Before the impression; excluded |
| c6 | i2 | 01 11:05 | 04 00:00 | Received at cutoff; excluded |
| c7 | missing | 01 12:00 | 01 12:01 | No matching impression |

## Expected result

| ad_id | impressions | clicked | ctr |
| --- | ---: | ---: | ---: |
| A1 | 2 | 1 | 0.5 |
| A2 | 1 | 1 | 1.0 |
| A3 | 1 | 0 | 0.0 |

There are four eligible impressions and two clicked impressions. The eligible
impression cost totals 1,000 cents. Row order does not matter.

## Your implementation

Edit [Lab01Ctr.compute](../../practice/spark/Lab01Ctr.java). Its two arguments are
`Dataset<Row>` values with the schemas above. Use Java Spark transformations or
register temporary views and return a Spark SQL query result.

```bash
bash practice/spark/run.sh --learner --lab 1 --output-dir my_results/learner
```

Before coding, write down the denominator and the grain of each intermediate
dataset. Then work in this order:

1. Select the eligible impression cohort using event date and receipt cutoff.
2. Identify eligible clicks using the receipt cutoff and attribution interval.
3. Reduce them to a set of clicked impression IDs.
4. Left join that set to the cohort.
5. Group by ad, count impressions and clicked impressions, and divide as floating
   point. Every output ad has a positive denominator by this contract.

Use `show()` on an intermediate result while learning. Avoid collecting a
production-sized dataset to the driver to compute the answer.

## Hints, from smallest to largest

1. Deduplicating only `click_id` does not remove multiple genuine clicks on one
   impression. The metric's numerator is at impression grain.
2. An inner join in the last stage would remove A3. A left join preserves its
   denominator.
3. `COUNT(*)` counts rows after the join. It is safe here only after the click
   side has at most one row per impression.
4. Express the upper attribution bound with `<`, not `<=`. Do not filter clicks
   to the impression calendar date.
5. The reference Java solution is `s01Ctr` and the SQL solution is `S01_SQL` in
   [SparkInterviewPractice.java](../../practice/spark/SparkInterviewPractice.java).
   Try your own implementation before opening those methods.

## A strong spoken walkthrough

**Opening:** “I want to confirm the metric first. We are measuring the share of
impressions with at least one valid click, using the impression day as the cohort.
This differs from counting all click events. I will use UTC, a 24-hour attribution
window, and the supplied receipt cutoff.”

**Before the join:** “The impression table is unique by impression ID. The click
table is one-to-many, so a direct join would repeat impressions. I will reduce the
valid click side to one row per impression before counting.”

**Checking the result:** “A1 has two impressions but only i1 is clicked. Its two
distinct clicks and a repeated delivery still contribute one to the numerator.
A2's click is after midnight but inside the attribution window. A3 remains with
zero clicks because its only click is exactly on the excluded upper bound.”

**Scaling:** “I would first filter and project the inputs, then inspect the join
and aggregation plan. I would not broadcast the click set just because it is
smaller than impressions; it must fit safely on executors. For a long tail of
slow tasks I would check key skew and partition sizes before adding memory.”

Adapt this language to your own understanding; the important parts are the
metric contract, cardinality, counterexample, and evidence.

## Follow-up questions and answer outlines

| Question | What a strong answer addresses |
| --- | --- |
| Why not `COUNT(click_id) / COUNT(impression_id)` after a join? | Repeated impression rows inflate the denominator; multiple clicks change the numerator's unit. |
| Can this CTR exceed 1? | Not under this clicked-impression definition with canonical data; an event-click rate can. |
| What if impressions are duplicated too? | Canonicalize by a documented event/version rule before cohorting; do not silently pick a random conflicting record. |
| What if late clicks arrive tomorrow? | Publish an as-of snapshot or rerun the affected cohort under a versioned cutoff; keep the metric definition stable. |
| How do you compare SQL and Java implementations? | Run both against the same expected fixture output, including boundaries and zero-click ads. |
| How would you find the top ads? | Apply minimum-volume eligibility, then rank within the required category; choose and explain a tie policy. |
| Is the highest raw CTR the best ad to serve? | Not necessarily: uncertainty, position bias, relevance, value, and exploration matter. This exercise only computes the metric. |
| What do you inspect when it is slow? | Read volume, exchanges, skew, spill, task distribution, and join strategy; change one hypothesis and verify correctness again. |

## Done means

- The learner check passes with your implementation.
- You can explain every included and excluded input row without reading the code.
- You can write an equivalent SQL solution and describe the join cardinalities.
- You can name one correctness invariant and one measured signal that would
  justify a performance change.

Next: [Stage 1 of the learning path](../learning-path.md#stage-1-event-contracts-and-metrics).
