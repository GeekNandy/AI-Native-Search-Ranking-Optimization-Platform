# Application foundation: verification evidence

This report describes verification of the `application-foundation` change. It
separates CI results, local checks, and remaining manual exercises.

## CI result

On 21 September 2026, [GitHub Actions run 35658752310](https://github.com/GeekNandy/AI-Native-Search-Ranking-Optimization-Platform/actions/runs/35658752310)
passed for application commit `3bd69216883a1cba7e68cb63359b3471c7c71329`.
The job used JDK 21 and real PostgreSQL containers and ran:

```bash
./mvnw --batch-mode --no-transfer-progress -Pintegration verify
```

Maven reported `BUILD SUCCESS`: eight unit tests and 21 integration checks,
with zero failures, errors, or skipped tests. This validates compilation,
packaging, and the supplied HTTP/database contracts. The following documentation
update records that result without changing application or test code.

## Executed local checks

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

## Local environment limits

`./mvnw -B test` failed before compilation because the execution environment
could not resolve `repo.maven.apache.org` while fetching the existing Spring Boot
parent POM. This is a dependency-resolution failure, not a passing build or a
test result. The environment also has Java 17 rather than the configured JDK 21,
and has no Docker runtime.

Consequently, the checked-in JUnit unit suite, full application compilation,
PostgreSQL integration suite, Docker Compose startup, HTTP smoke script, and
restart/recovery exercise could not be executed locally. CI subsequently ran
the full build, unit suite, and PostgreSQL integration suite successfully.
Compose startup, the shell-based HTTP smoke test, and the manual persistence/
recovery exercise remain outstanding.

## Verification gate and remaining exercises

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
[operations guide](operations.md). Record their results before marking those
manual exercises complete.

The GitHub workflow runs the same integration gate on pull requests and pushes
to `master`. Passing it establishes these test contracts, not production capacity
or ranking effectiveness.
