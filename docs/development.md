# Development guide

The application foundation provides an ad catalog API backed by PostgreSQL. Use
JDK 21, Docker with Compose, and the checked-in Maven wrapper. The database is
the only Compose service; run the application from your IDE or Maven for fast
debugging. Maven Central and the container registry must be reachable on first use.

## Start locally

From the repository root:

```bash
cp .env.example .env
```

Choose a local password in `.env`. This file is ignored by Git. Compose reads it
automatically; Spring Boot does not. Export the same values into the shell used
to start the application:

```bash
set -a
source .env
set +a

java -version
docker compose up -d --wait postgres
./mvnw spring-boot:run
```

`java -version` must show 21 for this project's configured build. If using an IDE,
select JDK 21 for both the project and Maven, and supply the variables from `.env`
in the run configuration. Run
`AiNativeSearchRankingOptimizationPlatformApplication` as the entry point.

Flyway applies `V1__create_ads.sql` at startup. Keep existing migrations immutable
after they have been used in a shared environment; introduce a new migration for
subsequent changes. Avoid manual schema initialization alongside Flyway.

Both HTTP and the published PostgreSQL port bind to loopback by default.
PostgreSQL uses a named volume so records survive application and container
restarts. The `postgres:17` image follows minor updates within major version 17;
pin a reviewed digest when an exactly reproducible deployed image is required.

## Exercise the application

In another terminal:

```bash
curl -i http://127.0.0.1:8080/actuator/health/readiness

curl -i http://127.0.0.1:8080/api/v1/ads \
  -H 'Content-Type: application/json' \
  -d '{"title":"Camera","category":"Electronics"}'
```

Creation returns `201`, an ad document, and a relative `Location` header. Copy the
returned UUID into the next request:

```bash
curl -i http://127.0.0.1:8080/api/v1/ads/REPLACE_WITH_RETURNED_UUID
```

For an automated smoke check, install `curl` and `jq` and run:

```bash
bash scripts/smoke-test.sh
```

The script checks readiness, creates one persistent ad, reads it back, and checks
a rejected request. It intentionally leaves that ad in the development database.
An optional first argument overrides the base URL. It does not retry writes.

## Verify changes

```bash
# Fast domain and application tests; no Docker daemon needed at execution time.
./mvnw test

# Full build, unit tests, and real HTTP/PostgreSQL integration tests.
./mvnw -Pintegration verify
```

Integration tests start disposable PostgreSQL containers with dynamic ports and
credentials. They do not use `.env` or the Compose database. The integration
profile runs `*IT` classes through Maven Failsafe; `test` runs the unit tests
through Surefire. An unavailable Docker runtime causes integration verification
to fail rather than silently reporting skipped coverage.

The GitHub Actions workflow runs the full verification command on pull requests
and changes to `master`, with JDK 21. Successful local fixture checks do not
substitute for that gate.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/search_ranking` | Application connection URL |
| `DB_USERNAME` | `search_ranking` | Database login; also used by Compose |
| `DB_PASSWORD` | Required | Database password; no application default |
| `DB_PORT` | `5432` | Compose host port; update `DB_URL` if changed |
| `SERVER_PORT` | `8080` | Application HTTP port |
| `SERVER_ADDRESS` | `127.0.0.1` | Application bind address |

The example environment values are for local development. An externally hosted
database requires its own credentials and connection/TLS configuration.

## Stop and restart

Use Ctrl-C for the application. Shutdown allows up to 20 seconds for the web
server's graceful shutdown phase, then closes the datasource. To stop Compose:

```bash
docker compose down
```

This retains the named volume. A later `docker compose up -d --wait postgres`
uses the same database. Do not add `--volumes` when you want to retain its contents.
Changing initialization credentials in `.env` does not change credentials already
stored inside an existing PostgreSQL volume.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Maven cannot resolve the parent POM | Network/DNS access to Maven Central; resolution fails before Java compilation |
| Release 21 is unsupported | Maven is using an older JDK; inspect `./mvnw -v` |
| Missing password or authentication failure | Export `.env` for the application; check credentials of an existing volume |
| Port already occupied | Change `SERVER_PORT`, or change both `DB_PORT` and `DB_URL` |
| Flyway checksum mismatch | Restore the applied migration and add a new migration for the change |
| Integration tests cannot locate Docker | Start a supported Docker runtime and run `docker info` |
| Readiness returns 503 | Check PostgreSQL and application logs; liveness can still be UP |

## Current verification evidence

The JDK 21 build, eight unit tests, and 21 PostgreSQL integration checks passed
in GitHub Actions. The local implementation environment has Java 17, no Docker,
and could not resolve Maven Central, so the manual Compose startup, smoke, and
restart/recovery exercises remain pending. See the
[verification report](verification.md) for the tested commit and CI run.

## Primary references

- [Spring Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Spring Boot testing with containers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [PostgreSQL container image](https://hub.docker.com/_/postgres)
- [Testcontainers PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/)
