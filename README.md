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
             (OIDC + Admin REST)       (module 2; a WireMock stub for now)
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

**2. Everything at once.** One command starts Nexus, builds the image and starts
the stack:

```bash
make
```

`make help` lists the rest — `make test`, `make it`, `make down`, `make logs`,
`make reset-db` (empties Keycloak and the person database, e.g. before running
the Postman collection again).
Requires make, which ships with Linux and macOS; on Windows install it once
with `winget install ezwinports.make`; it runs from Git Bash, PowerShell or the
IDE. The steps behind it, if you would rather run them by hand, are below.

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

**4. Start the stack by hand.** Images are pinned in `.env`. The individuals-api
image builds the application itself and downloads `person-client` from Nexus,
so Nexus goes first (it must already hold the artifact - `make publish`):

```bash
docker compose up -d --wait nexus
docker compose up -d --build
```

person-service has no code in module 1, so compose runs a WireMock stub under
its name (`infra/person-service-stub`): every registration gets a new
`userUid`, and the two Postman users get **409** on a second registration.

| | URL |
|---|---|
| individuals-api | http://localhost:8081 |
| Swagger UI (our contract) | http://localhost:8081/swagger-ui.html |
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
├── person-service/       Spring Boot service over the user aggregate (module 2, in progress)
├── infra/                prometheus, tempo, loki, alloy, grafana provisioning,
│                         person-service stub
├── docs/                 PlantUML diagrams + two cheatsheets
├── postman/
└── docker-compose.yml
```

Modules never depend on each other through `project(":...")`. Cross-module
contracts travel as artifacts, exactly as they would between separately
deployed services.

Inside `individuals-api`, one rule decides where a class lives: **shared code
sits at the level that covers everyone who uses it.** `gateway` holds one client
per external system (`KeycloakClient`, `PersonClient`) and is the only door to
the outside world, `service` (`UserService`, `TokenService`) decides what happens
and what to do when it breaks, and nothing above a client ever holds a foreign
payload.

## Documentation

| File | What it is |
|---|---|
| [`CONTEXT.md`](CONTEXT.md) | The working document: decisions, rules, the registration flow, progress. The long read. |
| [`CONTEXT.ru.md`](CONTEXT.ru.md) | Russian mirror. English wins if the two disagree. |
| [`docs/puml-diagrams/`](docs/puml-diagrams) | Diagrams: registration and its rollback, `/me`, the clients, how a failure becomes a response — and [`observability.puml`](docs/puml-diagrams/observability.puml), which is the one to open first if the metrics, logs and traces blur into one thing. A Russian mirror of all of them lives in [`docs/puml-ru`](docs/puml-ru). |
| [`person-service/docs/`](person-service/docs) | person-service's own diagrams, kept inside the module: [`person-service-flow.puml`](person-service/docs/puml-diagrams/person-service-flow.puml) — a user created through person-service, and the compensation that deletes it when Keycloak fails. Russian mirror in [`puml-ru`](person-service/docs/puml-ru). |
| [`postman/`](postman) | Two Postman collections. `individuals-api`: two users through every endpoint, the failures, Swagger and metrics, with the tokens carried between requests for you - import it, press Run on a fresh stack. `person-service`: one user through all five operations, every RFC 9457 failure and the delete that individuals-api uses as compensation; the user is deleted at the end, so it runs again on the same database. |

## Status

Module 1 is not finished. What is honest as of today:

| | |
|---|---|
| ✅ Working | Contract, Gradle build, Keycloak realm, `KeycloakClient` and `PersonClient`, `UserService` with compensation, `TokenService`, request validation, the whole error layer, `AuthController` — **the four endpoints are served and have been called for real** — all eight meters, and JSON logs carrying every field the module requires |
| 🐳 Compose | `make up` brings up eleven services and builds the app inside Docker. Prometheus scrapes our meters and a dashboard plots them, Loki holds our logs with their `traceId`, Tempo answers with our traces |
| 🧪 Tests | **53 tests, green: 36 unit and 17 integration.** Every test case the handout lists is covered, and each carries its code — `UT-REG-001`, `IT-KC-001` — in its display name. Integration runs against a real Keycloak and a real PostgreSQL in containers. Coverage on the key services is **100%**, with the build failing below 80% |
| 📮 Postman | `postman/individuals-api.postman_collection.json` — two users, 20 requests, tokens captured automatically, 52 assertions |
| 📦 Nexus | In the compose file, and `person-client` is resolved from it rather than from the local Maven repository |
| 🚧 In progress | **Module 2 — `person-service`.** The build, the contract (`/api/v1/users`, five operations, RFC 9457 errors), the generated `person-service-client`, the Flyway migrations, the JPA entities, the mappers, the service with its transactions, the Envers audit, the controller with Swagger UI, the RFC 9457 error layer and a Postman collection (19 requests, 93 assertions) are in; observability comes next. Until it runs, a WireMock stub answers in its place |

The ordered to-do lists live in §8 (module 1) and §8a (module 2) of `CONTEXT.md` and are kept current.
