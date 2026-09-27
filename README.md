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
| `POST` | `/api/v1/auth/registration` | — | **201 (Created)** with a token pair |
| `POST` | `/api/v1/auth/login` | — | **200 (OK)** with a token pair |
| `POST` | `/api/v1/auth/refresh-token` | — | **200 (OK)** with a fresh token pair |
| `GET`  | `/api/v1/auth/me` | `Bearer` | **200 (OK)** with the current user |

Every failure answers in one shape, built from a single `ErrorCode` constant
that carries the code, the HTTP status and the message together.

One status is not one answer. Three different things arrive as
**401 (Unauthorized)**, and the `error` field is what tells them apart:
`INVALID_CREDENTIALS` when a password was compared and did not match,
`AUTHENTICATION_REQUIRED` when no usable token was sent, and
`REFRESH_TOKEN_INVALID` when a refresh token is spent. A client shows a
different screen for each, and one code could not ask for all three.

## Running it

**1. Environment.** Copy the template and set the Keycloak client secret; `.env`
itself is never committed.

```bash
cp .env.example .env
# KEYCLOAK_CLIENT_SECRET must match the value in
# individuals-api/src/main/resources/realm/realm-export.json
```

**2. Everything at once.** One command builds the jar and starts the stack:

```bash
make
```

`make help` lists the rest — `make test`, `make it`, `make down`, `make logs`.
Requires make, which ships with Linux and macOS; on Windows install it once
with `winget install ezwinports.make` and **run it from Git Bash** — started
from PowerShell it runs every recipe through cmd, which has no `sh`. The steps
behind it, if you would rather run them by hand, are below.

**3. Build by hand.** This folder is a Git root, not a Gradle project: every
module is an independent build with its own wrapper, and they reach each other
only through published artifacts. So `person-client` is built and published
first, and `individuals-api` resolves it by coordinates. Until Nexus is up, the
local Maven repository covers that:

```bash
cd person-client && ./gradlew publishToMavenLocal && cd ..
cd individuals-api && ./gradlew build && cd ..
```

`build` runs the integration tests too, so **Docker has to be running** — they
start a Keycloak and a PostgreSQL of their own, and the stack itself should be
down, or the two compete for memory. For the fast loop use `./gradlew test`
inside `individuals-api`, which is unit tests only and needs nothing.

**4. Start the stack by hand.** Images are pinned in `.env`, so the stack is
reproducible and an upgrade is one deliberate edit in one file. The image only
packages the jar built in step 3, so build the jar first when the code changed:

```bash
cd individuals-api && ./gradlew bootJar && cd ..
docker compose up -d --build
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
| [`docs/puml-diagrams/`](docs/puml-diagrams) | Diagrams: registration and its rollback, `/me`, the gateway layer, how a failure becomes a response — and [`observability.puml`](docs/puml-diagrams/observability.puml), which is the one to open first if the metrics, logs and traces blur into one thing. A Russian mirror of all of them lives in [`docs/puml-ru`](docs/puml-ru). |
| [`postman/`](postman) | Postman collection: every endpoint plus its failures, with the tokens carried between requests for you. Import it, press Run. |

## Status

Module 1 is not finished. What is honest as of today:

| | |
|---|---|
| ✅ Working | Contract, Gradle build, Keycloak realm, all three gateways, `RegistrationService` with compensation, `AuthenticationService`, request validation, the whole error layer, `AuthController` — **the four endpoints are served and have been called for real** — all eight meters, and JSON logs carrying every field the module requires |
| 🐳 Compose | `docker compose up` brings up nine services and the app runs inside Docker. Prometheus scrapes our meters and a dashboard plots them, Loki holds our logs with their `traceId`, Tempo answers with our traces |
| 🧪 Tests | **33 tests, green: 22 unit and 11 integration.** Every test case the handout lists is covered, and each carries its code — `UT-REG-001`, `IT-KC-001` — in its display name. Integration runs against a real Keycloak and a real PostgreSQL in containers. Coverage on the key services is **100%**, with the build failing below 80% |
| 📮 Postman | `postman/individuals-api.postman_collection.json` — ten requests, tokens captured automatically, 31 assertions |
| 📦 Nexus | In the compose file, and `person-client` is resolved from it rather than from the local Maven repository |
| 🚧 Missing | `person-service` itself is module 2 — migrations only — so registration reaches it and stops there with **503** unless something answers in its place |

The ordered to-do list lives in §8 of `CONTEXT.md` and is kept current.
