# Experiments

An experiment freezes its ID, salt, treatment percentage and treatment model.
Control uses the deterministic baseline; treatment stays pinned even when the
default deployment changes. Lifecycle may move to stopped; resuming requires a
new experiment identity.

Create through `POST /api/v1/admin/experiments`, then include the ID in eligible
search requests. Decide eligibility before assignment; do not select users based
on their result or subsequent click.

```json
{"id":"ranking-2026-09","salt":"allocation-v1","modelVersion":"ctr-v1","treatmentPercent":50}
```

Length-prefixed `(experiment ID, salt, user ID)` is SHA-256 hashed into 10,000
buckets. PostgreSQL persists the first assignment and enforces arm exclusivity.
This provides deterministic pseudorandom allocation, not authentication or
anonymization.

## Report definition

The estimand is the difference in **assigned-user probability of an attributed
click within 24 hours of assignment**. The randomized user is the analysis unit.
Users without exposure/clicks remain in the denominator. Follow-up matures after
25 hours. A fixed `asOf` excludes later receipts.

Reports contain counts, rates, Wilson 95% arm intervals and an absolute
treatment-minus-control difference with a Newcombe-style interval. A chi-square
allocation check uses the declared percentage and flags sample-ratio mismatch
above the 1-df `p=0.001` threshold when expected counts are at least five. Fewer
than 100 mature users per arm triggers a small-sample flag; that is not a power
calculation.

No automated winner is declared. A real test needs a prespecified population,
primary metric, minimum effect, power/sample size, duration, stopping rule and
guardrails. Repeated peeking is not justified by fixed-horizon intervals.

## Stop behavior

`POST /api/v1/admin/experiments/{id}/stop` is idempotent. Subsequent requests with
that ID serve the baseline without new assignments or experiment-tagged search
requests. In-flight requests can finish with their already-resolved assignment.
Historical assignments and events remain auditable.

Early stopping changes treatment exposure and report interpretation. The report
includes active/stopped state; do not treat a stopped experiment as a completed
fixed-horizon test. Default deployment rollback does not stop pinned experiments:
stop each affected experiment during incident response.

Synthetic demonstrations are not A/B evidence. Marketplace interference, bots,
identifier changes, missing events and eligibility changes need explicit design
before a real experiment.
