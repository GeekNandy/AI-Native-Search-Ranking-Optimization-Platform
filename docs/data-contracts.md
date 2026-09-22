# Interaction and feature contracts

## Grain and identity

| Dataset | One row represents | Identity |
| --- | --- | --- |
| Catalog | An ad | Server UUID |
| Search requests | A committed search | Server request UUID |
| Search results | A returned ad and captured features | `(request_id, ad_id)`; unique position |
| Impressions | An observed exposure | Event UUID plus unique `(request_id, ad_id)` |
| Clicks | A click event on an impression | Event UUID |
| Assignments | A user's first assignment | `(experiment_id, user_id)` |
| Features | An ad's counts in a snapshot | `(snapshot_id, ad_id)` |
| Training examples | A frozen mature impression | `(export_id, impression_id)` |

Retries retain the same UUID and payload. Reused IDs with changed content, and
second impression IDs for an existing request/ad exposure, are conflicts.
Distinct clicks may reference the same impression; labels and CTR use an
existence test so that impression counts only once.

## Time and attribution

Timestamps use UTC/offset instants and PostgreSQL `TIMESTAMPTZ`. Events normalize
to microseconds. Receipt time comes from the server.

- Exposure occurs in `[search creation, search creation + 1 hour)`.
- New events arrive within one hour of event time; future events are rejected.
- Click attribution is `[impression time, impression time + 24 hours)`.
- A click preceding its impression's delivery gets `409`; retry after the
  exposure arrives, retaining the event ID.
- Labels and denominators mature after **25 hours**, covering attribution and
  accepted delivery delay. Identical stored retries remain valid after that.

Clock synchronization is part of the client contract. Invalid/late events are
rejected explicitly; there is no silent quarantine or unbounded late-event
reconciliation. A changed lateness policy requires a new contract and affected
dataset versions.

## CTR and labels

`clicked-impression CTR = mature impressions with an attributed click / all
eligible mature impressions`.

The denominator starts from impressions; `EXISTS` avoids multiplying rows in a
one-to-many click join and retains zero-click observations. Time ranges are
half-open, with receipt visibility bounded by `asOf`. The training label is the
same binary attributed-click outcome. It is not raw click count, purchase
conversion, unique-user CTR or a relevance judgment.

## Features and availability

The `click-v1` vector has this exact order:

| Feature | Definition | Cold-start value |
| --- | --- | --- |
| `textRelevance` | PostgreSQL full-text rank, normalization option 32 | Query-specific |
| `smoothedCtr` | `(clickedImpressions + 1) / (impressions + 20)` | `0.05` |
| `logImpressions` | `log(1 + impressions)` | `0` |

Snapshots aggregate mature historical impressions inside a declared window.
The server sets actual publication availability; importers cannot backdate it.
Search selects a snapshot available at prediction time and at most 24 hours old.
Missing per-ad history uses the cold defaults.

Search stores the features actually used. Training reads these captured inputs;
it does not attach today's snapshot to yesterday's prediction. This preserves
the information boundary across later snapshots, events and model changes.

## Frozen exports

An export UUID and cutoff identify examples materialized by one database
statement. Metadata and rows commit together. An identical retry reuses the
export; a different cutoff under that ID is `409`. Keyset pages by impression
UUID read those frozen labels/features while live ingestion continues.

`scripts/export-training.sh` assembles the pages into a new JSON Lines file.
Spark hashes its bytes, validates the contract and writes canonical Parquet.
Duplicate impression IDs, missing/invalid features, inconsistent request times,
incompatible schema versions and immature/future examples fail validation.

`GET /admin/training-data` is a live inspection endpoint. It does not provide a
transactional view across pages. Use frozen exports for training: a fixed cutoff
alone does not freeze concurrent database commits.
