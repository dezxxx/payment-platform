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
              accounts & tokens          the individual
                      ▼                        ▼
             Keycloak      :8080       person-service  :8082
             (OIDC + Admin REST)       (module 2: the individual aggregate with its
                                        address and personal data)
```

Two systems, two identifiers, and they are not interchangeable:

| Identifier | Issued by | Means |
|---|---|---|
| `user_uid` | person-service | The platform identifier. The only one the business layer uses. |
| `sub` | Keycloak | Which account a token belongs to. A technical link, nothing more. |

Registration writes to both systems and there is no transaction across them, so
the flow carries an explicit **compensation**: when the Keycloak part fails, the
half-made account is deleted and so is the individual in person-service, and the
caller gets the original error. See §2 of `individuals-api/CONTEXT.md`.

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
`make verify` checks everything from scratch in order: person-service and
transaction-service tests, their clients into Nexus, individuals-api tests,
images without cache, the stack up. Each module has its own commands too -
`make person-test`, `make transaction-publish-local`, `make individuals-it`
and so on; `make help` lists them by module. The integration tests need the
stack down, so `make verify-down` stops it first. Nexus credentials come from
`.env`.
Requires make, which ships with Linux and macOS; on Windows install it once
with `winget install ezwinports.make`; it runs from Git Bash, PowerShell or the
IDE. The steps behind it, if you would rather run them by hand, are below.

**3. Build by hand.** This folder is a Git root, not a Gradle project: every
module is an independent build with its own wrapper, and they reach each other
only through published artifacts. So person-service publishes
`person-service-client` first, and `individuals-api` resolves it by coordinates.
Until Nexus is up, the local Maven repository covers that:

```bash
cd person-service && ./gradlew publishClientPublicationToMavenLocal build && cd ..
cd individuals-api && ./gradlew build && cd ..
```

`build` runs the integration tests too, so **Docker has to be running** — they
start a Keycloak and PostgreSQL of their own, and the stack itself should be
down, or the two compete for memory. For the fast loop use `./gradlew test`
inside a module, which is unit tests only and needs nothing.

**4. Start the stack by hand.** Images are pinned in `.env`. The individuals-api
image builds the application itself and downloads `person-service-client` from Nexus,
so Nexus goes first (it must already hold the artifact - `make publish`):

```bash
docker compose up -d --wait nexus
docker compose up -d --build
```

person-service starts once its PostgreSQL is healthy, and individuals-api once
person-service is: registration begins there. The two Postman users get **409**
on a second registration, because person-service already holds their email.

| | URL |
|---|---|
| individuals-api | http://localhost:8081 |
| Swagger UI (our contract) | http://localhost:8081/swagger-ui.html |
| person-service | http://localhost:8082 |
| Swagger UI of person-service | http://localhost:8082/swagger-ui.html |
| Keycloak | http://localhost:8080 |
| Grafana (dashboards individuals-api and person-service) | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

The full port map, including why Nexus runs on 8083 instead of its own default,
is in §4 of `CONTEXT.md`.

## Layout

```
payment-platform/
├── individuals-api/      the orchestrator — module 1's deliverable
├── person-service/       the individual aggregate (module 2); publishes person-service-client to Nexus
├── transaction-service/  wallets and transactions (module 3), port 8084; design approved, code in progress
├── infra/                prometheus, tempo, loki, alloy, grafana provisioning
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
| [`CONTEXT.md`](CONTEXT.md) | What every module shares: rules, ports, how the platform is built, started and checked. |
| `<module>/CONTEXT.md` | What was decided and done in that module: [`individuals-api`](individuals-api/CONTEXT.md), [`person-service`](person-service/CONTEXT.md), [`transaction-service`](transaction-service/CONTEXT.md). |
| `CONTEXT.ru.md` next to each | Russian mirror. English wins if the two disagree. |
| [`individuals-api/diagrams-c4/`](individuals-api/diagrams-c4), [`person-service/diagrams-c4/`](person-service/diagrams-c4), [`transaction-service/diagrams-c4/`](transaction-service/diagrams-c4) | C4 diagrams of each service: container, component and sequence. PlantUML in `uml/{en,ru}`, a rendered picture of each in `png/{en,ru}`; transaction-service keeps English only. |
| [`postman/`](postman) | Two Postman collections. `individuals-api`: two users through every endpoint, the failures, Swagger and metrics, with the tokens carried between requests for you - import it, press Run on a fresh stack. `person-service`: one individual through all six operations, every RFC 9457 failure, the soft delete and the compensation individuals-api calls; each run takes a fresh email, so it runs again on the same database. |

## Status

Module 1 is not finished. What is honest as of today:

| | |
|---|---|
| ✅ Working | Contract, Gradle build, Keycloak realm, `KeycloakClient` and `PersonClient`, `UserService` with compensation, `TokenService`, request validation, the whole error layer, `AuthController` — **the four endpoints are served and have been called for real** — all eight meters, and JSON logs carrying every field the module requires |
| 🐳 Compose | `make up` brings up eleven services and builds the app inside Docker. Prometheus scrapes our meters and a dashboard plots them, Loki holds our logs with their `traceId`, Tempo answers with our traces |
| 🧪 Tests | **individuals-api: 67 tests, green — 50 unit and 17 integration**; every test case the module 1 handout lists carries its code (`UT-REG-001`, `IT-KC-001`) in its display name, against a real Keycloak and PostgreSQL in containers; coverage on the key services 100%. **person-service: 57 tests, green — 38 unit and 19 integration** on a real PostgreSQL, every scenario of the module 2 handout; **97% of lines**. Both builds fail below 80% |
| 📮 Postman | `postman/individuals-api.postman_collection.json` — two users, 20 requests, tokens captured automatically, 52 assertions |
| 📦 Nexus | In the compose file. person-service publishes `person-service-client` into it, and individuals-api resolves the client from it by coordinates |
| ✅ Module 2 | **`person-service`**: the contract (`/api/v1/individuals`, six operations, RFC 9457 errors), Flyway migrations, JPA, transactions, the Envers audit, Swagger UI, metrics, JSON logs and traces with a Grafana dashboard, a Docker image in compose, tests, and `person-service-client` in Nexus. individuals-api registers through it, with address and personal data, and compensates a failed Keycloak part by deleting the individual. Done, merged into `main` |
| 🚧 Module 3 | **`transaction-service`**: wallets, deposit, transfer and withdrawal over two PostgreSQL shards (ShardingSphere-JDBC), deposit and withdrawal finished through Kafka. The design - C4 diagrams with every failure branch - is approved; the code is in progress on `module-3-transaction-service` |

The ordered to-do lists live in the "Progress" section of each module's `CONTEXT.md` and are kept current.
