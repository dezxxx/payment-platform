# payment-platform

A payment platform built from independent Spring Boot services. **individuals-api**
is the only external entry point: registration, login, the current user, wallets,
deposit, withdrawal and transfer. Behind it, Keycloak holds the accounts,
person-service the individuals, transaction-service the wallets and transactions,
a payment provider finishes deposits and withdrawals over Kafka, and
currency-rate-service keeps hourly exchange rates for transfers between currencies.

---

## Stack

| | |
|---|---|
| Language, build | Java 25, Gradle 9.5.1 (Kotlin DSL), one build per service |
| Framework | Spring Boot 4.1: WebFlux in individuals-api, Web MVC in the other services |
| API | OpenAPI 3 contract first, OpenAPI Generator 7.25.0: server interfaces, DTOs, Spring HTTP Service clients |
| Identity | Keycloak 26.7 (OIDC, Admin REST API), OAuth2 resource server with JWT |
| Data | PostgreSQL 18, Spring Data JPA, Flyway, Hibernate Envers + Spring Data Envers, ShardingSphere-JDBC 5.5 (two shards) |
| Messaging | Apache Kafka 4.3 (KRaft), Avro 1.12, Confluent Schema Registry 8.3, transactional outbox |
| Observability | Micrometer, Prometheus 3, Grafana 12, Loki 3 (JSON logs), Tempo 2 (OpenTelemetry traces), Grafana Alloy |
| Artifacts | Sonatype Nexus 3: generated clients and event schemas |
| Tests | JUnit 5, Mockito, Testcontainers (PostgreSQL, Keycloak, Kafka), Awaitility, JaCoCo, Postman + newman |
| Runtime | Docker, Docker Compose, Make |

## Services

```
             ┌──────────────────────────────────────────────────────────────────────────────┐
  client ──▶ │  individuals-api      :8081                                                  │
             │  registration · login · refresh · /me · wallets · money                      │
             └────────┬───────────────────┬───────────────────┬───────────────────┬─────────┘
                      │                   │                   │                   │
              accounts & tokens     the individual    wallets & transactions  exchange rates
                      ▼                   ▼                   ▼                   ▼
             Keycloak  :8080      person-service :8082  transaction-service  currency-rate-service
             (OIDC + Admin REST)  (the individual with  :8084                :8088
                                   its address and      (two PostgreSQL      (hourly from Open
                                   personal data)        shards)              Exchange Rates)
                                                               │ Kafka
                                                               ▼
                                                        fake-payment-provider :8086
```

| Service | Port | What it does | Storage |
|---|---|---|---|
| `individuals-api` | 8081 | Reactive orchestrator. Calls Keycloak, person-service, transaction-service and currency-rate-service, owns no domain data; a circuit breaker on each platform service | — |
| `person-service` | 8082 | The individual aggregate: individual, user, address. Soft delete, audit history, compensation of a failed registration | PostgreSQL, Envers history schema `person_history` |
| `transaction-service` | 8084 | Wallets and transactions: deposit, withdrawal, transfer, status, search. Sends payment requests to Kafka through an outbox, applies the provider's answers idempotently | PostgreSQL, two shards by `user_uid` |
| `fake-payment-provider` | 8086 | Payment provider stub. Reads deposit and withdrawal requests from Kafka and answers with completed or failed events | — |
| `currency-rate-service` | 8088 | Exchange rates. Takes the rates from Open Exchange Rates once an hour (ShedLock: one instance at a time), computes every pair of the active currencies through USD, answers the rate of a pair valid now or at a given moment; called only by individuals-api | PostgreSQL |

**Identifiers.** `user_uid` is issued by person-service and is the only user
identifier the business layer uses. `sub` is the Keycloak account of a token.

**Service access.** person-service, transaction-service and currency-rate-service accept only a Keycloak
token with the realm role `SERVICE`, held by the service account of
individuals-api: no token gives **401**, a user's token **403**. transaction-service
takes the user from the `X-User-Uid` header.

## API

The contract is `individuals-api/openapi/individuals-api.yaml`; Swagger UI at
http://localhost:8081/swagger-ui.html.

| Method | Path | Auth | Answers |
|---|---|---|---|
| `POST` | `/api/v1/auth/registration` | — | **201** with a token pair |
| `POST` | `/api/v1/auth/login` | — | **200** with a token pair |
| `POST` | `/api/v1/auth/refresh-token` | — | **200** with a fresh token pair |
| `GET` | `/api/v1/auth/me` | `Bearer` | **200** with the current user |
| `POST` | `/api/v1/wallets` | `Bearer` | **201** with the new wallet |
| `GET` | `/api/v1/wallets` | `Bearer` | **200** with the user's wallets |
| `GET` | `/api/v1/wallets/{walletUid}` | `Bearer` | **200** with the wallet |
| `POST` | `/api/v1/transactions/{deposit,withdrawal,transfer}/init` | `Bearer` | **200** with amount, fee, total, `expiresAt` and a signed `offerToken`; a transfer between currencies adds `rate` and `targetAmount` |
| `POST` | `/api/v1/transactions/{deposit,withdrawal,transfer}/confirm` | `Bearer` | **200** with `transactionId` and status |
| `GET` | `/api/v1/transactions/{transactionId}/status` | `Bearer` | **200** with the transaction |
| `GET` | `/api/v1/transactions` | `Bearer` | **200** with a page of transactions, filtered by wallet, type, status and period |

**Errors.** Every failure has one body (`ErrorResponse`) with an `error` code.
**401** carries `INVALID_CREDENTIALS` (wrong password), `AUTHENTICATION_REQUIRED`
(no usable token) or `REFRESH_TOKEN_INVALID` (spent refresh token).

**Registration.** individuals-api creates the individual in person-service, then
the account in Keycloak. When the Keycloak part fails, the account and the
individual are deleted and the caller gets the original error.

**Money operations.** `init` stores nothing and returns an `offerToken` signed with
`TRANSACTION_OFFER_SECRET`, valid 5 minutes. `confirm` takes only that token and
carries out exactly its terms; a repeated `confirm` with the same token returns
the same transaction. A transfer is finished at once. A deposit or withdrawal
stays `PENDING` until the provider answers over Kafka; then it becomes
`COMPLETED` or `FAILED`, and a failed withdrawal returns the reserved money.

**Transfer between currencies.** The user's two wallets have different
currencies: individuals-api takes the current rate from currency-rate-service,
the client never sends one. The target wallet gets `targetAmount` = amount × rate,
rounded half-up to cents; the fee is charged in the source currency. The rate and
`targetAmount` are frozen in the `offerToken`. A conversion below 1.00 USD is
refused with **400** `AMOUNT_TOO_SMALL`; no valid rate for the pair gives **422**
`RATE_NOT_FOUND`.

**Unavailable services.** A platform service that times out, answers 5xx or is
unreachable gives **503** `DEPENDENCY_UNAVAILABLE`. When at least half of its last
10 calls failed (5 calls at least), its circuit breaker answers 503 at once for
30 seconds, then lets one trial call through.

The internal contracts are `person-service/openapi/person-service.yaml`,
`transaction-service/openapi/transaction-service.yaml` and
`currency-rate-service/openapi/currency-rate-service.yaml`, with Swagger UI on
ports 8082, 8084 and 8088.

## Running it

**Requirements:** Docker with Compose, Make (Linux and macOS have it; on Windows
`winget install ezwinports.make`, run from Git Bash, PowerShell or the IDE),
Node.js for newman.

**1. Environment.** `.env` is never committed.

```bash
cp .env.example .env
```

| Variable | Value |
|---|---|
| `KEYCLOAK_CLIENT_SECRET` | the client secret from `individuals-api/src/main/resources/realm/realm-export.json` |
| `TRANSACTION_OFFER_SECRET` | any long random string, e.g. `openssl rand -base64 32`; transaction-service does not start without it |
| `OXR_APP_ID` | the App ID of a free Open Exchange Rates account (https://openexchangerates.org/signup/free); without it currency-rate-service gets no rates |
| `NEXUS_USERNAME`, `NEXUS_PASSWORD` | Nexus credentials for `make publish` |

Image versions and host ports are set in `.env` too.

**2. Start.**

```bash
make            # Nexus, then the images (built inside Docker), then the whole stack
make help       # every command
```

| Command | Does |
|---|---|
| `make up` / `make down` | start / stop the stack, volumes kept |
| `make reset-db` | stop, wipe Keycloak, person-service, both transaction shards, currency-rate-service and Kafka, start |
| `make rebuild` | rebuild the images without cache, start |
| `make logs`, `make ps` | follow the application logs, list the containers |
| `make test` | unit tests of every service, nothing needs to run |
| `make it` | integration tests of every service; stops the stack first |
| `make verify-down` | stop the stack, run every test, publish the clients and events to Nexus, rebuild the images, start |
| `make person-test`, `make transaction-it`, … | the same per service: `person-`, `individuals-`, `transaction-`, `currency-`, `provider-` + `build`, `jar`, `test`, `it`, `clean` |

Integration tests need Docker running and the stack down.

**3. Build by hand.** Each service is a separate Gradle build with its own
wrapper. person-service publishes `person-service-client`, transaction-service
publishes `transaction-service-client` and `transaction-service-events`,
currency-rate-service publishes `currency-rate-service-client`;
individuals-api and fake-payment-provider resolve them by coordinates from
Nexus or the local Maven repository:

```bash
cd person-service      && ./gradlew publishClientPublicationToMavenLocal build && cd ..
cd transaction-service && ./gradlew publishToMavenLocal build && cd ..
cd currency-rate-service && ./gradlew publishClientPublicationToMavenLocal build && cd ..
cd individuals-api     && ./gradlew build && cd ..
cd fake-payment-provider && ./gradlew build && cd ..
```

`./gradlew test` runs the unit tests only.

**4. Start by hand.** The images download the clients from Nexus, so Nexus goes
first and must hold them (`make publish`):

```bash
docker compose up -d --wait nexus
docker compose up -d --build
```

| | URL |
|---|---|
| individuals-api, Swagger UI | http://localhost:8081, http://localhost:8081/swagger-ui.html |
| person-service, Swagger UI | http://localhost:8082, http://localhost:8082/swagger-ui.html |
| transaction-service, Swagger UI | http://localhost:8084, http://localhost:8084/swagger-ui.html |
| currency-rate-service, Swagger UI | http://localhost:8088, http://localhost:8088/swagger-ui.html |
| Keycloak | http://localhost:8080 |
| Grafana: a dashboard per service | http://localhost:3000 |
| Prometheus, alerts on `/alerts` | http://localhost:9090 |
| Kafka UI: topics, consumer groups, messages | http://localhost:8087 |

The full port map is in §4 of `CONTEXT.md`.

## Checking it

| Collection | Requests | Assertions | Covers |
|---|---|---|---|
| `postman/individuals-api.postman_collection.json` | 86 | 200 | a warm-up of every service first (the first call after a start is slow), two users through every endpoint, wallets and money, transfers between currencies at the live rate from USD and from UAH (two rates, the 1.00 USD minimum through UAH → USD), every failure, Swagger, metrics; run it on a fresh stack (`make reset-db`) |
| `postman/person-service.postman_collection.json` | 31 | 124 | all six operations, every RFC 9457 failure, soft delete, compensation; a fresh email on each run |
| `postman/transaction-service.postman_collection.json` | 87 | 212 | wallets, a deposit completed and one declined by the provider, withdrawal, transfer, status, every search filter, every error; fresh users on each run |
| `postman/currency-rate-service.postman_collection.json` | 24 | 51 | live rates and how they relate (reverse pair, cross rate through USD), currencies, providers, every RFC 9457 failure, metrics, a direct call without the service token refused |

A status poll repeats its request until the payment provider answers, so a run may count more.

Every collection takes the service token first (individuals-api for its warm-up): set its
`clientSecret` variable to `KEYCLOAK_CLIENT_SECRET`.

```bash
npx newman run postman/transaction-service.postman_collection.json --env-var clientSecret=<KEYCLOAK_CLIENT_SECRET>
```

| Service | Tests | Unit | Integration |
|---|---|---|---|
| individuals-api | 154 | 104 | 50, real Keycloak and PostgreSQL |
| person-service | 65 | 38 | 27, real PostgreSQL; 97% of lines |
| transaction-service | 240 | 126 | 114, two real PostgreSQL shards and Kafka |
| currency-rate-service | 57 | 27 | 30, real PostgreSQL, a stub of the rate provider |
| fake-payment-provider | 8 | 6 | 2 |

individuals-api, person-service and currency-rate-service fail the build below 80% line coverage.

## Layout

```
payment-platform/
├── individuals-api/        the orchestrator, the only external entry point
├── person-service/         the individual aggregate; publishes person-service-client
├── transaction-service/    wallets and transactions; publishes its client and events
├── currency-rate-service/  hourly exchange rates; publishes currency-rate-service-client
├── fake-payment-provider/  the payment provider stub; answers over Kafka
├── infra/                  Prometheus, Tempo, Loki, Alloy, Grafana provisioning
├── postman/                Postman collections
├── docker-compose.yml
└── Makefile
```

Each service has `openapi/` (its contract), `diagrams-c4/` (C4 container,
component and sequence diagrams in PlantUML)
and `CONTEXT.md` (its design).

In `individuals-api`, `gateway` holds one client per external system
(`KeycloakClient`, `PersonClient`, `TransactionClient`, `CurrencyRateServiceClient`) and is the only package
that talks to the outside; `service` (`UserService`, `TokenService`) holds the
business flow, including the transfer between currencies (wallet currencies → rates → transfer init).
