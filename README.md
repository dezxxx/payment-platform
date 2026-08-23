# payment-platform

A monorepo built along the *Java Backend Developer* course
([education.proselyte.net](https://education.proselyte.net)). Module 1 delivers
**individuals-api** — the platform's external entry point: it registers users,
logs them in and answers "who am I", orchestrating Keycloak and person-service
without owning any domain data of its own.

Java 25 · Spring Boot 4.1 · WebFlux · Keycloak 26.7 · Gradle 9.5.1 · Docker Compose

---

## What it does

```
             ┌──────────────────────────────────────────┐
  client ──▶ │  individuals-api      :8081              │
             │  registration · login · refresh · /me    │
             └────────┬────────────────────────┬────────┘
                      │                        │
              accounts & tokens          the domain user
                      ▼                        ▼
             Keycloak      :8080       person-service  :8082
             (OIDC + Admin REST)       (module 2 — migrations only for now)
```

Two systems, two identifiers, and they are not interchangeable:

| Identifier | Issued by | Means |
|---|---|---|
| `user_uid` | person-service | The platform identifier. The only one the business layer uses. |
| `sub` | Keycloak | Which account a token belongs to. A technical link, nothing more. |

Registration writes to both systems and there is no transaction across them, so
the flow carries an explicit **compensation**: an account created without a
password is deleted again. See §4 of `CONTEXT.md`.

## The API

Contract-first: `individuals-api/openapi/individuals-api.yaml` is the source,
the Java interface is generated from it.

| Method | Path | Auth | Answers |
|---|---|---|---|
| `POST` | `/v1/auth/registration` | — | **201 (Created)** with a token pair |
| `POST` | `/v1/auth/login` | — | **200 (OK)** with a token pair |
| `POST` | `/v1/auth/refresh-token` | — | **200 (OK)** with a fresh token pair |
| `GET`  | `/v1/auth/me` | `Bearer` | **200 (OK)** with the current user |

Every failure answers in one shape, built from a single `ErrorCode` constant
that carries the code, the HTTP status and the message together.

## Running it

**1. Environment.** Copy the template and set the Keycloak client secret; `.env`
itself is never committed.

```bash
cp .env.example .env
# KEYCLOAK_CLIENT_SECRET must match the value in
# individuals-api/realm/realm-export.json
```

**2. Build.** `person-client` must exist as an artifact before
`individuals-api` can resolve it. Until Nexus is up, the local Maven repository
covers that:

```bash
./gradlew :person-client:publishToMavenLocal
./gradlew build
```

**3. Start the stack.** Images are pinned in `.env`, so the stack is
reproducible and an upgrade is one deliberate edit in one file.

```bash
docker compose up -d
```

| | URL |
|---|---|
| individuals-api | http://localhost:8081 |
| Keycloak | http://localhost:8080 |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

The full port map, including why Nexus runs on 8083 instead of its own default,
is in §5 of `CONTEXT.md`.

## Layout

```
payment-platform/
├── individuals-api/      the orchestrator — module 1's deliverable
├── person-client/        generated DTOs + HTTP client, published to Nexus
├── person-service/       contract + Flyway migrations only (module 2)
├── infra/                prometheus, tempo, loki, alloy, grafana provisioning
├── docs/                 PlantUML diagrams + two cheatsheets
├── postman/
└── docker-compose.yml
```

Modules never depend on each other through `project(":...")`. Cross-module
contracts travel as artifacts, exactly as they would between separately
deployed services.

Inside `individuals-api`, one rule decides where a class lives: **shared code
sits at the level that covers everyone who uses it.** `gateway` is the only
door to the outside world, `service` decides what happens and what to do when
it breaks, and nothing above a gateway ever holds a foreign payload.

## Documentation

| File | What it is |
|---|---|
| [`CONTEXT.md`](CONTEXT.md) | The working document: decisions, rules, the registration flow, progress. The long read. |
| [`CONTEXT.ru.md`](CONTEXT.ru.md) | Russian mirror. English wins if the two disagree. |
| [`docs/CHEATSHEET.md`](docs/CHEATSHEET.md) | Every port, credential, endpoint, status code and command on one page. Russian mirror: [`CHEATSHEET.ru.md`](docs/CHEATSHEET.ru.md). |
| [`docs/CLASSES.md`](docs/CLASSES.md) | One line per class: what it is and its single job. Open it next to the IDE. |
| [`docs/*.puml`](docs) | Sequence and class diagrams — registration, rollback, `/me`, the gateway layer. |

## Status

Module 1 is not finished. What is honest as of today:

| | |
|---|---|
| ✅ Working | Contract, Gradle build, Keycloak realm, all three gateways, `RegistrationService` with compensation, `AuthenticationService`, the whole error layer |
| 🚧 Missing | `AuthController` — **the endpoints above are not served yet**; the `confirmPassword` validation; the metrics |
| ❓ Unverified | The Dockerfile has never been built; Loki has never received a log line from `individuals-api`; Nexus is not in the compose file |
| 🧪 Tests | One unit test. Integration tests on Testcontainers and the JaCoCo threshold are still ahead |

The ordered to-do list lives in §8 of `CONTEXT.md` and is kept current.
