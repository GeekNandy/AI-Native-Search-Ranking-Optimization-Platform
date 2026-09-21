# Ad catalog API

Base URL for local development: `http://127.0.0.1:8080`.
The machine-readable contract is [openapi.yaml](openapi.yaml). This increment
provides catalog creation and lookup. An ad is a catalog entry; impressions,
clicks, bids, campaigns, and ranking decisions are separate future concepts.

## Create an ad

`POST /api/v1/ads`, `Content-Type: application/json`

```json
{"title":"Camera","category":"Electronics"}
```

| Field | Contract |
| --- | --- |
| `title` | Required text, 1–200 Unicode code points in the submitted value |
| `category` | Required text, 1–64 Unicode code points in the submitted value |

Fields cannot be null, blank, or contain Unicode control characters. After
validation, surrounding whitespace is removed with Java `String.strip()`.
Case and interior spacing are preserved. Category is currently a free-form label,
not a reference to a managed taxonomy. No Unicode normalization such as NFC is
applied. Unknown JSON properties are rejected to surface misspelled fields.
Numbers and booleans are not coerced into text, and trailing JSON values are
rejected. A request must contain one JSON object matching the declared schema.

The server generates a UUID and UTC creation timestamp. Timestamps use
microsecond precision to match PostgreSQL, so create and read responses agree.

Successful response: `201 Created`, with
`Location: /api/v1/ads/<id>` and a body of this shape:

```json
{
  "id": "2834ebc2-dfa7-4af3-8e73-d130bb92f421",
  "title": "Camera",
  "category": "Electronics",
  "createdAt": "2026-09-21T10:00:00.123456Z"
}
```

The response is returned after the insert succeeds. PostgreSQL owns durability;
there is no application memory cache serving as the source of truth.

## Read an ad

`GET /api/v1/ads/{id}`

Returns `200` and the same representation for an existing UUID. A valid UUID
that does not exist returns `404`; an unparseable UUID returns `400`.

## Errors

Errors use `application/problem+json` with Problem Details fields. For example:

```json
{
  "type": "about:blank",
  "title": "Invalid request",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "errors": [{"field":"title","message":"must not be blank"}]
}
```

Validation messages may vary with the validation provider and locale. Clients
should use the HTTP status and field names rather than matching message text.
Responses do not echo rejected values, SQL exceptions, credentials, or stacks.

| Status | Meaning |
| --- | --- |
| `400` | Invalid fields, unknown JSON properties, unreadable JSON, or invalid UUID |
| `404` | Ad does not exist |
| `405` | Method is not supported for the route |
| `415` | Creation request has an unsupported content type |
| `503` | Database access failed or timed out |
| `500` | Unexpected server error; details remain in server logs |

## Retry and concurrency semantics

`GET` can be retried. `POST` is not idempotent: submitting the same body twice
creates two distinct ads. Duplicate titles are allowed because they are not
business identifiers. There is no check-then-insert race on title uniqueness.

A connection failure or timeout can leave a write outcome unknown, even if the
client receives an error. Do not blindly retry creation. A future idempotency
feature would require a durable request key and payload-consistency contract.
This API does not claim exactly-once creation.

Independent creates use separate database statements and UUID primary keys.
The application service is stateless and the repository uses Spring's JDBC
client with a bounded connection pool. A single insert uses PostgreSQL's
statement transaction; there is no multi-step transaction in this feature.

## Health and exposure

- `GET /actuator/health`: aggregate health.
- `GET /actuator/health/liveness`: application liveness, independent of the DB.
- `GET /actuator/health/readiness`: readiness state plus database connectivity.

Health details are hidden. A database outage is expected to make readiness fail
with `503`, while liveness stays `200` if the application is otherwise alive.

This foundation is configured for local use. Authentication, authorization,
tenant isolation, public ingress, and rate limits require a separate deployment
design before external exposure.
