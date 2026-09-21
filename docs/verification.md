# Application foundation: verification evidence

This report describes verification of the `application-foundation` change. It
separates executed checks from tests supplied for an environment with JDK 21,
Docker, and Maven Central access.

## Executed checks

| Check | Result | Scope |
| --- | --- | --- |
| Core source compilation | Passed with available OpenJDK 17.0.20 | Actual `Ad`, `AdRepository`, and `AdService` sources; not the configured JDK 21 application build |
| Standalone core behavior | Eight checks passed | Normalization, UTC microsecond precision, persisted identity/read, missing identity, validation before persistence, Unicode boundary, independent create identities, persistence failure propagation |
| Java parsing | All 15 application/test source files parsed | Syntax only; does not resolve dependencies or type-check framework calls |
| Maven POM | XML parsed | No effective-POM or dependency-resolution claim |
| Compose, CI, and OpenAPI files | YAML parsed | No Docker Compose runtime or full OpenAPI validator available |
| OpenAPI references | Internal references resolved | Document integrity, not observed HTTP conformance |
| Smoke script | `bash -n` passed | Shell syntax only; no HTTP server was available |
| Documentation | Local linked file targets checked | Documentation navigation |
| Source hygiene | Whitespace checks passed | Modified and newly added text files |

The core checks used an isolated in-memory implementation of the repository
interface to exercise application behavior. They do not validate JDBC, Flyway,
PostgreSQL, Bean Validation, JSON mapping, or HTTP behavior.

## Blocked checks

`./mvnw -B test` failed before compilation because the execution environment
could not resolve `repo.maven.apache.org` while fetching the existing Spring Boot
parent POM. This is a dependency-resolution failure, not a passing build or a
test result. The environment also has Java 17 rather than the configured JDK 21,
and has no Docker runtime.

Consequently, the checked-in JUnit unit suite, full application compilation,
PostgreSQL integration suite, Docker Compose startup, HTTP smoke script, and
restart/recovery exercise have not been executed here. These results describe
local verification; consult the pull request's checks for subsequent CI results.

## Required verification gate

With JDK 21, a supported Docker runtime, and dependency access:

```bash
./mvnw -Pintegration verify
```

This runs the unit tests and two integration classes. `AdApiIT` starts the real
application and PostgreSQL and checks create/read persistence, input boundaries,
Unicode handling, JSON types, invalid/missing IDs, methods/media types, SQL
parameter binding, migration constraints, and health exposure.
`DatabaseAvailabilityIT` owns a separate database and stops it to check `503`
responses and the distinction between liveness and readiness.

Then follow [local startup](development.md), run
`bash scripts/smoke-test.sh`, and perform the persistence/recovery steps in the
[operations guide](operations.md). Record the actual build output and update
this report before treating the foundation as fully verified.

The GitHub workflow runs the same integration gate on pull requests and pushes
to `master`. Passing it establishes these test
contracts, not production capacity or ranking effectiveness.
