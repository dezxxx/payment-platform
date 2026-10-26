# CONTEXT - individuals-api

Module 1; module 3 adds wallets and transactions (section 6). Shared rules, ports and how the platform is started: [`CONTEXT.md`](../CONTEXT.md).

Module 1 delivers **individuals-api**: the external entry layer that
orchestrates user registration and authentication. It stores nothing itself.

---

## 1. Architecture

### Reactive stack — Spring WebFlux, not Spring MVC

Required by the handout. Netty instead of Tomcat, `Mono`/`Flux` instead of plain
return types, `WebClient` instead of `RestClient`, `SecurityWebFilterChain`
instead of `SecurityFilterChain`. Nothing in the service may block the event
loop: no `.block()`, no blocking JDBC, no blocking HTTP client. Both OpenAPI
modules are generated with `reactive = true`, so the contracts themselves are
`Mono`-typed and a blocking implementation would not even compile against them.

### Application meters

| Meter | Type | Meaning |
|---|---|---|
| `auth.registration` | counter | registration attempts |
| `auth.registration.success` | counter | successful registrations |
| `auth.registration.failure` | counter | failed registrations |
| `auth.login` | counter | logins |
| `auth.login.failure` | counter | failed logins |
| `auth.refresh` | counter | token refreshes |
| `external.keycloak.requests` | timer | Keycloak call duration |
| `external.person_service.requests` | timer | person-service call duration |

### individuals-api layers

Mandatory chain, one direction only:

```
rest -> service -> gateway -> external system
```

The first package is `rest`, not `controller`: this service speaks REST and
nothing else — no view layer, no server-rendered page, no message consumer — so
the package is named after the protocol it serves rather than after the Spring
stereotype inside it. `api` was not available, that name belongs to the
generated code.

| Layer | Holds | Never does |
|---|---|---|
| `rest` | accepts and returns DTOs, implements the generated `AuthApi` | business logic; direct calls to Keycloak or person-service |
| `service` | orchestration of the registration and login scenarios | HTTP, JSON, Keycloak or person-client types |
| `gateway` | every outbound call, one client per external system | domain decisions |
| `validation`, `error`, `config`, `metrics` | validation rules, error mapping, security wiring, meters | anything belonging to another layer |

As in the teacher's component diagram: `AuthController` calls two services,
and each external system has exactly one client.

- `UserService` — registration and `/me`; its last registration step gets the
  tokens through `TokenService.issueTokens`, as the component diagram draws
  `UserService -> TokenService`
- `TokenService` — login and refresh
- `KeycloakClient` — everything Keycloak: the token endpoint (login, refresh,
  the service-account token, cached) and the Admin API (account, password,
  role, rollback, registration date)
- `PersonClient` — wraps the generated `PersonsApi`: create the person, get
  its `user_uid`

Each client turns the other side's errors into our `ApiException` itself, in a
private `translate` method - there are no separate translator classes.
`util/GatewayErrors` is shared by both: "the other side did not answer at
all" becomes **503**.

**Identifiers.** The business layer knows only the domain `user_uid`. The
Keycloak `sub` is stored as a technical link (`keycloak_user_id`) and never
becomes the platform's primary identifier. Nothing above the gateway layer is
allowed to address a user by it.

**Why clients rather than repositories.** individuals-api owns no data, so it
has no repository. The client takes that seat in the layer chain and is
injected into the service through the constructor. The rule that Service and
Controller never get invented interfaces of their own still holds.

Patterns in play: `PersonClient` and `KeycloakClient` are **Adapters** - they
translate the other side's types and errors into ours, so nothing above them
holds a foreign payload. `KeycloakClient` is also a **Facade** over Keycloak's
two APIs.

### Compensation policy

The order - the individual first, the Keycloak account second - guarantees no
Keycloak account is ever left without an individual. The inverse case is real:
if the Keycloak steps fail, the individual already exists in person-service.

When that happens the service:

- deletes the half-made Keycloak account if the password or the role failed
  (`KeycloakClient.rollbackAccountWithError`);
- removes the whole individual through
  `DELETE /api/v1/individuals/compensate-registration/{id}` (`UserService.compensate`),
  so the same email can register again;
- returns the original error to the client;
- if that delete fails too, answers `503 REGISTRATION_INCONSISTENT` and logs the
  `user_uid` for a manual clean-up - the partial failure is never hidden.

Nothing here silently retries or pretends the registration succeeded.

### Keycloak realm

`realm/realm-export.json` must define:

- a realm of its own for the course
- a confidential client for individuals-api
- a service account on that client for the Admin REST API
- a mapper putting `user_uid` into the token, or a consistent way to read it
  through `/me`
- the roles needed to read and manage users (`view-users`, `manage-users` from
  `realm-management`)
- `upConfig` declaring `user_uid` as a user profile attribute — see below

The client secret is a credential: it lives in `.env`, never in the export
committed to git.

JSON carries no comments, so the file is described block by block in
`realm/README.md`, together with the facts it repeats elsewhere in the project.

#### Why `upConfig` is not optional

Since Keycloak 24 the declarative user profile is always on, and unmanaged
attributes are **disabled** by default. An attribute that the profile does not
declare is dropped silently: `POST /admin/realms/{realm}/users` still answers
`201`, the account is created, and the attribute is simply not there. No error,
no warning. The protocol mapper then has nothing to map and the claim never
appears in the token.

That is exactly what happened here, and it was invisible until a token was
decoded. The fix is to declare `user_uid` in `upConfig`. Declaring `upConfig`
replaces the whole default profile, so `username`, `email`, `firstName` and
`lastName` have to be listed again with their original validations.

Editing the user profile needs `manage-realm`, which our service account
deliberately does **not** have — it is configuration, not runtime work, so it
belongs in `realm-export.json` and nowhere else.

#### Verified against a running Keycloak 26.7.2

Checked end to end after a `down -v` and a fresh import:

| Check | Result |
|---|---|
| `client_credentials` on `individuals-api` | token issued — client is confidential, secret matches `.env`, service account is on |
| `GET /admin/realms/{realm}/users` with that token | `200` — `view-users` really is granted |
| create user with `credentials` + `attributes` in one call | `201`, account complete |
| password grant as that user | tokens issued, and `user_uid` present as a claim |
| same, but `"temporary": true` | `400 invalid_grant`, `Account is not fully set up` |
| create a second user with an existing email | `409`, `User exists with same email` |
| password grant with a wrong password | `400 invalid_grant`, `Invalid user credentials` |

Two consequences for the gateway code:

- Keycloak answers a bad password with **400**, not 401. `KeycloakClient`
  translates it: our contract says 401, and the client must never see 400 here.
- `400 invalid_grant` covers both a wrong password and a not-fully-set-up
  account. The status code alone cannot tell them apart — only
  `error_description` can. Log the description, return 401.

### Deviations from the course handout, and why

| Handout | Here | Reason |
|---|---|---|
| `generatorName("java")` | `"spring"` + `library("spring-http-interface")` | the same handout mandates Spring HTTP Service Clients, not a generic Java client |
| `$buildDir` | `layout.buildDirectory` | `$buildDir` was removed in Gradle 9 — the handout snippet does not compile here |
| spec kept in `common/` | spec kept in the module that owns it | matches the artifact table: `person-service/openapi/person-service.yaml` |
| Spring Boot 4.0.6 | 4.1.0 | 4.1.0 reached GA after the handout was written |
| `proselyte` / `com.example` | `dezxxx` / `com.dezxxx` | handout placeholders |
| Tempo traces under `/tmp/tempo/traces` | `/var/tempo`, with an explicit wal path | that is where docker-compose mounts the named volume; under `/tmp` the traces die with the container and the volume stays empty |
| `RestClient` as the standard HTTP client (and `Feign/OpenAPI` on the platform diagram) | Spring HTTP Service Clients over `WebClientAdapter` | The **style** the handout mandates - annotated `@HttpExchange` interfaces behind generated proxies - is followed exactly; `person-client` is generated from the OpenAPI document with `library = spring-http-interface`. Only the adapter differs. `RestClient` is synchronous and `RestClientAdapter` cannot return `Mono` at all, so adopting it would mean regenerating the client with `reactive = false` and wrapping every call in `Mono.fromCallable(...).subscribeOn(boundedElastic())`. A bare call would park a Netty event loop thread that serves hundreds of connections. The same handout's C4 diagram labels this service **Spring Boot WebFlux**, and the two requirements pull against each other; the reactive adapter is the one that keeps WebFlux worth having |
| postgres volume at `/var/lib/postgresql/data` | `/var/lib/postgresql` | PostgreSQL 18 moved the data directory one level down and refuses to start when the old path is mounted — the container exits with code 1 |

---

## 2. Registration flow (the core scenario)

Nine steps, in this order. The order **is** the design, not an implementation
detail: person-service goes first because it issues the identity, and a refusal
from it leaves nothing to undo anywhere.

1. `individuals-api` accepts the registration request
2. validates format, required fields and password confirmation
3. `POST /api/v1/individuals` — person-service creates the individual with its
   user and address
4. it answers with the individual's id - the `user_uid` the whole platform will use
5. obtains a service-account token (`client_credentials`)
6. `POST /admin/realms/{realm}/users` — creates the account, **without a
   password**, carrying `user_uid` as a user attribute
7. `PUT /admin/realms/{realm}/users/{id}/reset-password` — sets the password
8. assigns the realm role `USER` (Keycloak gives no role on its own)
9. logs in via `/realms/{realm}/protocol/openid-connect/token` (`password`) and
   returns access token, refresh token, lifetime and `user_uid`

Step 6 body — note what is *not* in it:

```json
{
  "username": "user@dezxxx.com",
  "email": "user@dezxxx.com",
  "firstName": "Ivan",
  "lastName": "Ivanov",
  "enabled": true,
  "emailVerified": false,
  "attributes": { "user_uid": ["6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33"] }
}
```

The account is created with **201** and an empty body; the new id exists only in
the `Location` header, and parsing it out is `KeycloakClient`'s job.

Step 7 body:

```json
{ "type": "password", "value": "Str0ngP@ssw0rd", "temporary": false }
```

`temporary: false` is mandatory. A temporary password makes Keycloak attach the
`UPDATE_PASSWORD` required action, and step 9 then fails with
`400 invalid_grant: Account is not fully set up` — after everything else looked
successful.

**Keycloak could accept the password inside step 6**, in a `credentials[]`
array, saving one round trip and making account creation atomic. The handout
names the `reset-password` endpoint explicitly, so the two-call form is what is
implemented; the cost is the gap described below.

### Where it can break, and what the client is told

| Where | Client sees | Created so far |
|---|---|---|
| `@Valid` | **400** `VALIDATION_ERROR`, one `details[]` line per bad field | nothing |
| person-service `409` | **409** `USER_ALREADY_EXISTS` | nothing |
| person-service `5xx` | **503** `DEPENDENCY_UNAVAILABLE` | nothing |
| person-service `400` | **500** `INTERNAL_ERROR` — their validation and ours disagree, which is our bug | nothing |
| *— `user_uid` issued; from here a failure is compensated —* | | |
| Keycloak create | the Keycloak error as is (**503** `DEPENDENCY_UNAVAILABLE`, **409** `USER_ALREADY_EXISTS`) | nothing — the individual is removed |
| Keycloak reset-password or role | the same | nothing — the account and the individual are removed |
| the compensation itself | **503** `REGISTRATION_INCONSISTENT` | the individual; `user_uid` in the log for a manual clean-up |
| login | **503** `DEPENDENCY_UNAVAILABLE` | everything — **not** an inconsistency |

The last row is the subtle one. If only the login failed, the individual and the
account both exist and work; we merely failed to hand tokens over, and the caller
can log in normally. So the login is outside the compensation.

**Compensation.** A failure at step 7 or 8 leaves an account that can never be
logged into while its email stays taken, so `KeycloakClient.rollbackAccountWithError`
deletes it first. Then, for any Keycloak failure, `UserService.compensate` calls
`DELETE /api/v1/individuals/compensate-registration/{id}`: person-service removes
the whole aggregate (a hard delete, unlike the soft `DELETE /api/v1/individuals/{id}`),
so a registration that never completed leaves no data and the email is free again.
The client gets the original error. Only if that delete fails too do the systems
disagree, and that is `REGISTRATION_INCONSISTENT` with the `user_uid` in the log.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Ctl as AuthController
    participant Svc as UserService
    participant PGw as PersonClient
    participant AGw as KeycloakClient
    participant Ps as person-service
    participant Kc as Keycloak

    Client->>Ctl: POST /api/v1/auth/registration
    Ctl->>Svc: register
    Note over Svc: format and password confirmation
    Svc->>PGw: create the individual
    PGw->>Ps: POST /api/v1/individuals
    Ps-->>PGw: 201, individual id
    PGw-->>Svc: user_uid = individual id
    Svc->>AGw: create account
    AGw->>Kc: POST token client_credentials<br/>(cached until 10 s before expiry)
    Kc-->>AGw: access token
    AGw->>Kc: POST /admin/realms/REALM/users<br/>user_uid attribute, no password
    Kc-->>AGw: 201 Location header
    AGw-->>Svc: keycloak user id
    Svc->>AGw: set password
    AGw->>Kc: PUT /admin/realms/REALM/users/ID/reset-password
    Kc-->>AGw: 204
    Svc->>AGw: assign role USER
    AGw->>Kc: POST .../role-mappings/realm
    Kc-->>AGw: 204
    Svc->>AGw: log in (through TokenService.issueTokens)
    AGw->>Kc: POST token grant_type=password
    Kc-->>AGw: access, refresh, expiresIn
    AGw-->>Svc: tokens, user_uid read from the token
    Svc-->>Ctl: TokenResponse plus user_uid
    Ctl-->>Client: 201
```

---

## 3. External API

| Method | Path | Auth |
|---|---|---|
| POST | `/api/v1/auth/registration` | none |
| POST | `/api/v1/auth/login` | none |
| POST | `/api/v1/auth/refresh-token` | none |
| GET | `/api/v1/auth/me` | Bearer |

### What each endpoint does

**`POST /registration`** — creates the individual, then the Keycloak account,
then logs in on the caller's behalf so the client never has to send a second
request. Answers `201` with `TokenResponse`. A duplicate email is a `409`, and
it can be raised by either side: `person-service` may already hold that email,
and Keycloak answers `User exists with same email` with its own `409`. Both map
to the same `409` outward — the client does not care which store objected.

**`POST /login`** — no database of ours is touched. Email and password go
straight to Keycloak as `grant_type=password`; on success `200` with
`TokenResponse`. Bad credentials come back from Keycloak as `400 invalid_grant`
and are translated to `401`.

**`POST /refresh-token`** — a thin wrapper over `grant_type=refresh_token`.
Keycloak normally rotates the refresh token too, so both tokens in the response
are fresh. `200` on success; an expired or revoked token is `401`.

**`GET /auth/me`** — protected. Spring Security validates the JWT against the
realm's JWKS, then the service makes two calls in parallel: the profile from
person-service by the token's `user_uid` (`PersonClient.findProfile` →
`GET /api/v1/individuals/{id}`), and `GET /admin/realms/{realm}/users/{id}` by
`sub` through `KeycloakClient`.

The call to Keycloak is deliberate, not laziness about parsing the token. A JWT
is a snapshot from the moment it was issued and stays valid for its lifetime, so
a token alone cannot tell us whether the account was disabled or deleted a
minute ago; the Admin API can. It also carries fields the token does not, such
as the creation timestamp. If the account is gone, `/me` answers `404`; if the
token itself is bad or expired, `401`.

The roles in `CurrentUserResponse` are read from the token's `realm_access`, not
from a second Admin API call — they are already there and cost nothing.

**`email`, `firstName` and `lastName` come from person-service, the owner of the
profile.** individuals-api still writes a copy of the names into Keycloak at
registration, and nothing keeps that copy in step: a PATCH in person-service
changes only the original, and every new token is built from the stale copy.
So nothing on the platform reads the names from the token. The token gives only
what is genuinely about the account: `user_uid`, `sub`, `roles`,
`email_verified`. An individual person-service no longer finds - archived by the
soft delete - answers `404` on `/me`, even with a valid token.

`registeredAt` is declared in `CurrentUserResponse` and comes from Keycloak's
`createdTimestamp`, which is epoch milliseconds. `KeycloakClient` converts
it to UTC and its method is `findRegisteredAt`, returning `Mono<OffsetDateTime>`
— no layer above the client sees the raw number, and none of them holds a
Keycloak payload either. The record it is parsed into,
`KeycloakUserRepresentation`, is a private record inside `KeycloakClient` with that one field;
it is named after Keycloak's own model so the name can be looked up in their
reference.

---

## 4. Progress — module 1

### Acceptance criteria

The module is accepted only when every line below is true. These are the
handout's, verbatim in meaning.

| # | Criterion |
|---|---|
| 1 | the monorepo contains individuals-api and person-service |
| 2 | the OpenAPI contracts exist and pass `openApiValidate` |
| 3 | registration works through the orchestration scenario |
| 4 | `user_uid` is written into Keycloak as a user attribute |
| 5 | `/login`, `/refresh-token` and `/me` work |
| 6 | the HTTP client with its DTOs is published to Nexus |
| 7 | `docker compose up --build` brings up the infrastructure and the app |
| 8 | `/actuator/health` and `/actuator/prometheus` are reachable |
| 9 | traces are visible in Grafana/Tempo |
| 10 | unit and integration tests exist |
| 11 | the main scenario is covered at 80% or better on the key services |
| 12 | README.md and CONTEXT.md are written |

Criterion 11 needs a coverage tool, and the build has none yet - JaCoCo is on
the list below. **JaCoCo is not named by the handout**; the 80% figure is, and
JaCoCo is simply what measures it.

### The student's own checklist

Twelve checks the handout hands over separately from the criteria above. They
overlap, but not completely: several of these are about *where a thing lives*
rather than whether it works, which no test can fail for us.

| # | Check | Where it stands |
|---|---|---|
| 1 | OpenAPI describes every external method and every error | ✅ all four operations, shared `ErrorResponse`, examples on each |
| 2 | no DTO written by hand over a contract that already describes it | ✅ `useBeanValidation` + generated models only; `git grep` finds no hand-written model |
| 3 | individuals-api stores no domain data of the user | ✅ no database at all - see §1 of `../CONTEXT.md`, and the `/me` debt in §3 |
| 4 | person-service is named as the source of domain truth | ✅ §1 of `../CONTEXT.md`, first table |
| 5 | `user_uid` runs end to end and is never replaced by `sub` | ✅ enforced by IT-KC-002, which compares against the value person-service issued |
| 6 | every outbound call is inside a client | ✅ `gateway/` is the only package holding a foreign payload |
| 7 | metrics reachable through `/actuator/prometheus` | ✅ IT-OBS-001 |
| 8 | traces leave over OTLP to Tempo | ✅ IT-OBS-002 asserts the push; Tempo's own indexing verified by hand on the live stack |
| 9 | logs are JSON and carry correlation | ✅ ECS to stdout, `traceId` and `spanId` on every record - IT-OBS-003 |
| 10 | at least one integration test against Keycloak | ✅ three |
| 11 | person-service migrations are attached and runnable | ✅ IT-DB-001 runs them on a real PostgreSQL |
| 12 | `person-client` is published to Nexus | ❌ only `publishToMavenLocal`; same gap as criterion 6 |

Eleven of twelve. The one that is open is the one open acceptance criterion
too, plus criterion 11's coverage number - everything else the handout asks
for is done and proven rather than asserted.

### Mandatory test cases

**Unit** - orchestration, validation, error mapping, conflicts, parsing the
answers of external systems.

| Code | Scenario | Expected |
|---|---|---|
| UT-REG-001 | valid registration request | person-service is called first, then Keycloak, then tokens are returned |
| UT-REG-002 | password and confirmation differ | 400, Keycloak is never called |
| UT-REG-003 | person-service reports an email conflict | 409, Keycloak is never called |
| UT-REG-004 | individual created, Keycloak unreachable | 502/503, the partial failure is recorded |
| UT-LOG-001 | successful login | access and refresh tokens returned |
| UT-LOG-002 | wrong password | 401 |
| UT-REF-001 | successful token refresh | a new access token is returned |
| UT-ME-001 | valid bearer token | the current user is returned |

**Integration** - containers, real HTTP against Keycloak, tokens, actuator,
tracing.

| Code | Scenario | Expected |
|---|---|---|
| IT-KC-001 | registration against a real Keycloak container | the user appears in the realm |
| IT-KC-002 | after registration | the `user_uid` attribute is found in Keycloak |
| IT-KC-003 | `/login` against the real token endpoint | a real JWT comes back |
| IT-OBS-001 | `/actuator/prometheus` | Prometheus metrics are readable |
| IT-OBS-002 | after a request | a trace is visible in Tempo/Grafana |
| IT-OBS-003 | log records | carry `traceId` and `spanId` |
| IT-DB-001 | person-service migrations against PostgreSQL | Flyway succeeds |

**Where these classes must live.** `individuals-api/build.gradle.kts` filters
`test` to `com.dezxxx.individuals.unit.*` and `integrationTest` to
`com.dezxxx.individuals.integration.*`. A test placed anywhere else runs in
neither task and fails silently by never running at all.

### Done

- [x] Monorepo root `payment-platform`, git on `main`
- [x] Directory skeleton for all modules
- [x] `.gitignore`
- [x] `gradle/libs.versions.toml` — every version extracted
- [x] `gradle.properties` — coordinates, toolchain, Nexus
- [x] `settings.gradle.kts` — modules, Nexus + mavenLocal, placeholders for the
      next course modules
- [x] Root `build.gradle.kts` — toolchain 25, Lombok, test conventions
- [x] Gradle Wrapper 9.5.1
- [x] `person-client/build.gradle.kts` — generate + publish to Nexus
- [x] `individuals-api/build.gradle.kts` — Boot app, contract-first, split
      unit / integration test tasks
- [x] `person-service/build.gradle.kts` — contract validation only
- [x] `individuals-api/openapi/individuals-api.yaml` — 4 endpoints, shared
      error model, payload examples on every external method
- [x] `person-service/openapi/person-service.yaml` — the 3 frozen operations,
      schemas mirroring the DDL, same shared error model
- [x] Flyway `V001__init_person_schema.sql`
- [x] Flyway `V002__seed_countries.sql` — all 249 ISO 3166-1 entries
- [x] `.env` + `.env.example` + `docker-compose.yml` — images pinned, ports per
      the handout table
- [x] **First real `./gradlew build` — green.** See section 5.
- [x] `application.yml` / `application-docker.yml` / `logback-spring.xml`
- [x] `realm/realm-export.json` — realm, confidential client, service account,
      `user_uid` mapper, `upConfig`, realm-management roles
- [x] `individuals-api/Dockerfile` (never built yet)
- [x] `infra/` — prometheus, tempo, loki, alloy, grafana provisioning
- [x] `error/` — 7 classes, both roads (advice and security handlers)
- [x] `config/` — security, HTTP clients, Keycloak and person-service properties
- [x] `gateway/` — `KeycloakClient` and `PersonClient`, one per external system
- [x] `UserService` — registration with compensation, and `/me`
- [x] `TokenService` — login and refresh
- [x] `README.md` — first version: what it is, the four endpoints, how to run
      it, and an honest status table
- [x] `validation/` — `PasswordsMatch` and its validator, attached to the
      generated model from the contract; three tests, run through a real
      `Validator` so a rule that stops reaching the class cannot pass silently
- [x] `testRuntimeOnly(libs.junit.platform.launcher)` — the catalog entry had
      been there unused, and without it the test JVM refused to start at all,
      so no test in this module had ever run
- [x] `rest/AuthController implements AuthApi` — the four endpoints are served.
      **First live run of the API**, against a Keycloak in Docker: health
      **200**; `/me` without a token **401** in the contract's error shape; a
      mismatched confirmation **400** carrying
      `confirmPassword: must match password`; a well-formed registration
      **503**, having reached person-service, which is not running; an unknown
      login **401**, translated from Keycloak's `invalid_grant`
- [x] `UserServiceTest` (then `RegistrationServiceTest`) — six cases, given/when/then. Found a real
      defect on the first run: `then(login(...))` evaluates its argument while
      the chain is assembled, so the gateway was called before the password
      was set and even on registrations that failed. Harmless only because the
      gateway builds a lazy `WebClient` chain; fixed with `Mono.defer`
- [x] `metrics/AuthMetrics` — all eight meters, verified live in Prometheus
- [x] **`docker compose up` brings up the whole stack.** Nine services, and
      three defects surfaced in the process - see below
- [x] Four Grafana panels for our own meters. The provisioned dashboard drew
      only Spring's built-in `http_server_requests_*` and knew nothing about
      `AuthMetrics` - the meters existed and no panel looked at them, which is
      the failure mode the "a metric name is a contract" rule warns about
- [x] **The public prefix is `/api/v1/auth/...`, not `/v1/auth/...`.** The
      handout's own endpoint table omits the prefix while its curl examples and
      its `@HttpExchange("/api/v1/persons")` example both carry it; two
      independent places against one, and `person-service.yaml` was already on
      `/api/v1/persons`, so the repository had been contradicting itself.
      Caught by a counter: `/api/v1/auth/login` answered **401** exactly like
      the real path, because an unknown path is not in `permitAll` and the
      security entry point translates any filter rejection into
      `INVALID_CREDENTIALS`. The bodies were identical - only
      `auth_login_total` showed that one of the two never reached the service
- [x] `integrationTest` no longer fails the build. Gradle fails a `Test` task
      whose filter matches nothing, and `check` depends on it, so
      `./gradlew build` had been red since the JUnit launcher was wired in -
      the task had simply never been reached before that
- [x] **`logging/` - the fields the module requires on every record.** Three of
      the eight were there; the path, method, status, business error code and
      `user_uid` lived only inside the text of one message, and only on records
      written by `GlobalExceptionHandler`. `RequestLog` now travels in the
      Reactor Context and is copied into the MDC on every signal, because the
      MDC is thread-local and a reactive chain hops threads on each outbound
      call. That copy only happens when `spring.reactor.context-propagation` is
      exactly `auto`, which had never been set - an integration test proved the
      records come out with no trace id on the default. Set in
      `application.yml`, where production needs it too. A comment in that file
      had claimed the application already did all of this; it did not, and
      there was no MDC call anywhere in `main`
- [x] **Every test case the handout requires - all fifteen codes, 22 unit and
      11 integration, green.** `TokenServiceTest` and `UserServiceTest` close UT-LOG-001,
      UT-LOG-002, UT-REF-001 and UT-ME-001; `AuthControllerIT`,
      `ObservabilityIT` and `PersonSchemaMigrationIT` close the seven IT codes.
      person-service is stubbed on reactor-netty, since module 1 does not
      contain it, and Tempo is stubbed too - whether Tempo indexes what it is
      given is Tempo's promise, ours ends at the socket. Each code opens the
      `@DisplayName`, so a Gradle report matches the handout without guessing
- [x] **`REGISTRATION_INCONSISTENT` answers 503, not 500.** UT-REG-004 fixes
      502 or 503 for "individual created, Keycloak unreachable". The case for
      500 was that the split is ours rather than the dependency's, and that 503
      invites a retry now guaranteed to answer **409 (Conflict)**; the
      acceptance criteria win, and both sides are recorded on the constant. The
      test asserts the status rather than only the code - the mismatch had
      survived precisely because nothing did
- [x] `spring-boot-testcontainers` removed. Declared and never used:
      `@ServiceConnection` has nothing to attach to here, since Keycloak comes
      from a third party and the application opens no datasource of its own
- [x] **JaCoCo, and criterion 11 answered with a number: the key services are
      at 100%.** Generated code is excluded - `com.dezxxx.individuals.api` is
      models with getters, and counting them would move the percentage a long
      way while saying nothing. Both suites feed the report, because a gateway
      is exercised only by the integration tests. The 80% rule is scoped to
      `com.dezxxx.individuals.service` on purpose: a repository-wide average
      would hide a bare `UserService` behind a well covered `config`.
      What the report showed beyond the criterion: `gateway.keycloak` is at
      **0%** - its error translator only runs when Keycloak answers with a
      failure, and no test of ours makes it do that
- [x] **Nexus is in compose, and `person-client` really comes from it.**
      Publishing was proven by removing the artifact from `~/.m2` and from
      Gradle's cache and rebuilding with `--refresh-dependencies`: the build
      passed, and the only place left to get `com.dezxxx:person-client` from
      was Nexus. Closes criterion 6 and the last line of the student checklist.
      Three things had to be sorted out on the way:
      1. **Nexus answers an unauthenticated read with 403, not 401**, and
         Gradle only retries with credentials after a 401. Fixed with
         `authentication { create<BasicAuthentication>("basic") }` in both
         places - the publish repository and the resolve repository.
      2. **Community Edition refuses to serve repository content until its
         EULA is accepted**, answering 403 with an explanatory body. Accepted
         once, in the browser; the state lives in the `nexus-data` volume.
      3. The container's own port stays 8081 internally and is published on
         8083, because the handout gives 8081 to individuals-api.
      Upgrading the image from 3.87.1 to 3.96.1 kept the volume, the EULA and
      the published artifact - so the repository survives a version change, not
      only a restart.

- [x] **Every account gets the `USER` role, and `/me` answers with it alone.**
      The role was declared in the realm export and granted to nobody. Two
      declarative routes were tried and neither works: Keycloak builds its own
      `default-roles-<realm>` composite before it reads ours and keeps its own,
      and `realmRoles` in the create-user body is accepted with **201** and
      ignored. So `KeycloakClient.assignPlatformRole` grants it - two
      calls, because Keycloak resolves a role mapping by id and the id is known
      only after reading the role. Granted after the password, inside the block
      that compensates, so a failure there removes the half-made account like
      any other.
      That surfaced a missing permission: the service account had `view-users`,
      `query-users` and `manage-users`, but reading a realm role needs
      `view-realm`. Without it registration answered **503**, which is also the
      real reason a duplicate address did - not the missing person-service.
      `/me` filters Keycloak's own three roles out of the answer: they say what
      an account may do inside Keycloak, and a client reading `roles` is asking
      a business question.
- [x] `details` is always an array, empty when there is nothing to report. The
      handout's error example shows one, and a client that checks the length
      before reading `details[0]` then behaves the same on every error instead
      of telling an absent field from an empty one.
- [x] **Postman collection** - required by the handout's artifact list. Two
      users, Ivan and Anna, each registered, asked `/me`, refreshed and logged
      in, then a check that each token still answers for its own user; six
      failures; health, Swagger, the contract file and the metrics. Nothing is
      copied by hand. Verified with `newman`: 52 assertions, green.
- [x] **Reworked to the teacher's component diagram.** `AuthController` ->
      `UserService` / `TokenService` -> one `KeycloakClient`, plus `PersonClient`.
      The three gateways, both error translators and the Keycloak records
      folded into those two clients; Javadoc replaced by short `//` comments.
      The ten error-translation cases moved into `KeycloakClientTest`.
- [x] **The image builds the application itself** (section 6 of `../CONTEXT.md`), and compose
      runs a WireMock stub as `person-service`, so registration works end to
      end in module 1.
- [x] **Swagger UI shows the contract**, not a spec springdoc guesses from
      the code: `openapi/individuals-api.yaml` is copied into the jar and
      served at `/openapi/individuals-api.yaml`.
- [x] **A broken token answers in our error shape.** A present but
      undecodable token was rejected by the resource server's own entry point
      - an empty 401 with the decoder's message in `WWW-Authenticate`. Ours is
      now set on `oauth2ResourceServer` as well; `ErrorContractIT` pins it.
- [x] **Every scenario step is a named span**, so Tempo reads as the flow
      rather than as bare `http post`: `registration` ->
      `registration.createPerson`, `.createAccount`, `.resetPassword`,
      `.assignRole`, `.login` (and `.rollbackAccount` on failure); `login` ->
      `login.getToken`, `login.readUserUid`; the same for `refresh`; `me` ->
      `me.findRegisteredAt`. Named in the services, because only a service
      knows which scenario a call belongs to - `KeycloakClient.login` serves
      both registration and login. Done with `util/Spans`:
      `mono.name(step).tap(Micrometer.observation(registry))` from
      `reactor-core-micrometer`. Not an annotation: a method returning `Mono`
      returns before any work happens, so `@Observed` (which handles only
      `CompletionStage`) would close the span at ~0 ms, and `@WithSpan` needs
      the OpenTelemetry instrumentation starter - a second tracing system next
      to Boot's. `ObservabilityIT` checks every registration step reaches the
      collector.

### Two failures share 401, and the code is what tells them apart

`AUTHENTICATION_REQUIRED` - no usable token: none sent, expired, signed by an
unknown key, or a path that does not exist, since anything outside the public
list has to be authenticated before it can be routed.

`INVALID_CREDENTIALS` - a password was compared and did not match. The login
flow, and nothing else.

`REFRESH_TOKEN_INVALID` - the refresh token is spent: expired, already used,
or revoked. Keycloak answers `invalid_grant` to this and to a wrong password
alike, so `KeycloakClient` cannot tell them apart; `TokenService.refresh`
can, because no password reached it. A client reads this one as "the session
ended, show the login form".

Until this was split, `ApiAuthenticationEntryPoint` mapped every filter-chain
rejection to `INVALID_CREDENTIALS`, so a typo in a URL answered *"Email or
password is incorrect"* on a request that carried no password at all. The
status was right - **401 (Unauthorized)** is what a resource server owes an
unauthenticated caller - and the message sent the reader looking in the wrong
place.

**A path that does not exist still answers 401, not 404, and that is
deliberate.** Answering "no such path" to an unauthenticated caller maps out
the API for anyone who asks. `ErrorContractIT` pins both this and the split.

### What the first full compose run cost

The Dockerfile, `infra/tempo/tempo.yml` and the OTLP metrics registry had never
been exercised. All three were broken:

1. **The image did not build at all.** `publishToMavenLocal` and `bootJar` were
   asked for in one Gradle invocation; Gradle resolved individuals-api's
   compile classpath before the publish had written anything, fell through to
   the Nexus that does not exist inside the container, and died. Split into two
   invocations. Note for later: the Nexus URL is `localhost:8083`, which inside
   a container means the container itself - it will need the compose service
   name when Nexus arrives.
2. **Tempo accepted no traces.** The app was exporting to the right address and
   getting "connection refused" while Tempo answered `ready` on 3200. Tempo
   embeds the OpenTelemetry receiver, and since collector 0.104 an omitted
   `endpoint` binds to localhost rather than 0.0.0.0 - so the receiver was
   listening only to itself. Written out explicitly.
3. **A failed metrics push every minute.** The OpenTelemetry starter brings an
   OTLP meter registry along with the tracing it is there for, and it pushed to
   a receiver that does not exist - a stack trace a minute, forever. Prometheus
   collects ours by scraping, so the second copy is off:
   `management.otlp.metrics.export.enabled: false`.

Verified afterwards, end to end: Prometheus scrapes all eight meters and
returns the same numbers the app reports; Loki holds our JSON logs with
`traceId` and `spanId` promoted to labels; Tempo answers searches for
`service.name=individuals-api` with traces carrying the security filter chain's
spans.

### Next up, in this order

**Every acceptance criterion is met, every line of the student's checklist is
ticked, and every artifact the handout asks for exists.** What is left is not
required by it:

- [ ] Open questions for the teacher: path prefix `/api/v1` vs `/v1`, role
      name `USER` vs `individual.user`

---

## 5. First build run — what it cost

`./gradlew build` had never been executed before 2026-08-19. Versions had been
checked against Maven Central by hand, but nothing had been resolved for real.
Seven defects surfaced; all are fixed and the build is green from `clean`.

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | Wrapper could not download the distribution, `Connect timed out` after 10 s | `services.gradle.org` redirects to GitHub release assets; `networkTimeout=10000` with `retries=0` gave up on the first slow connect | `networkTimeout=120000`, `retries=3`, `retryBackOffMs=2000` |
| 2 | `Could not resolve com.dezxxx:person-client` → `Username must not be null!` | the `credentials { }` block was declared unconditionally; Gradle treats null credentials as a hard error, not as an unreachable repository, and aborts the whole resolution | credentials attached only when `NEXUS_USERNAME` **and** `NEXUS_PASSWORD` are set — in `settings.gradle.kts` and in the `person-client` publish repo |
| 3 | `Configuration cache state could not be cached` | downstream of #2 — an unresolvable classpath cannot be serialised | disappeared with #2 |
| 4 | `sourcesJar` used the output of `openApiGenerate` without declaring a dependency | the generated directory was added to `sourceSets` as a bare path, so only the hand-wired `compileJava` knew about it | the srcDir is now wired to the task provider, so every consumer inherits the dependency |
| 5 | `TemplateNotFoundException: pom-sb3.mustache` | the generator tried to emit a Maven `pom.xml` as a supporting file; the `spring-http-interface` library ships no such template | `globalProperties = { apis, models }` — APIs and models only, no supporting files |
| 6 | `package org.springframework.format.annotation does not exist` | generated models annotate date-time properties with `@DateTimeFormat`, which lives in `spring-context`, not `spring-web` | `spring-context` added to the catalog and to `person-client` |
| 7 | `Could not find org.testcontainers:junit-jupiter:` (empty version) | Boot 4.1 imports **Testcontainers 2.0.5**, and 2.x renamed every module artifact | `testcontainers-junit-jupiter`, `testcontainers-postgresql` |

Two more things the run settled:

- `val integrationTest by tasks.registering(...)` is deprecated as of Gradle 9.6
  and would not survive Gradle 10. Rewritten as
  `tasks.register<Test>("integrationTest")`. The build now reports no
  deprecations at all.
- `bootJar` failed with *"Main class name has not been configured"* — expected,
  since no application class existed yet. `IndividualsApiApplication` was added.

### Verified, not assumed

- Gradle 9.5.1 runs the whole build; 9.7.1 was verified to exist and work too,
  but is held back by the IDE — see "Why Gradle 9.5.1" in section 9 of `../CONTEXT.md`.
- Spring Boot 4.1.0, OpenAPI Generator 7.14.0, Lombok 1.18.46 all resolve.
- `person-client` generates `PersonsApi` with `@HttpExchange` — Spring HTTP
  Service Clients, no Feign, as rule 4 requires.
- `individuals-api` generates `AuthApi` as an interface only.
- All three specs pass `openApiValidate`.
- `clean build` runs from clean and produces `individuals-api.jar`.

### Contract alignment with the handout

`ErrorResponse` now matches the model the handout fixes for the whole course:
`required` is `[timestamp, path, status, error, message, traceId]`, and
`details` is an **array of strings**. The earlier `ErrorDetail` object carried
more structure, but five services will share this model — consistency wins.
Field information survives as `"confirmPassword: must match password"`.

`UserResponse` was renamed to `CurrentUserResponse` to match the handout's
required schema list.

---

## 6. Module 3 — wallets and transactions through transaction-service

The client works with wallets and money only through individuals-api. It is
the single place where the token is checked, so it is also the single place
`user_uid` may come from. Diagrams: `diagrams-c4/uml/en/container.puml`,
`component.puml`, `sequence-wallets-transactions.puml`.

### Decisions

| Topic | Decision | Why |
|---|---|---|
| Where `user_uid` comes from | only from the verified token (`KeycloakClaims.requireUserUid`); individuals-api sets it as `X-User-Uid` on every call | it is the same `user_uid` person-service issued at registration and Keycloak carries in the token; taken from the client, anyone could read another user's wallets |
| Trust boundary | transaction-service checks no token and trusts `X-User-Uid`, so it is reachable from the Docker network only; its port is published locally for development and its own Postman collection | the header is safe only while individuals-api is the only caller |
| Endpoints | the 11 operations of transaction-service under the same paths; own wallets are `GET /api/v1/wallets` (→ `/api/v1/wallets/user/{user_uid}`), search takes no `userUid` (added from the token) | the client never names a user |
| Client | `TransactionClient` in `gateway/transaction`, an adapter over the generated `transaction-service-client` (`WalletsApi`, `TransactionsApi` beans on `transactionWebClient`), like `PersonClient` | one client per external system |
| Classes | `WalletController`, `TransactionController` → `WalletService`, `TransactionService` → `TransactionClient` | `rest -> service -> gateway`, as in section 1 |
| Configuration | `individuals.transaction-service.base-url` from `TRANSACTION_SERVICE_URL` (`http://localhost:8084` locally, `http://transaction-service:8084` in Docker), `response-timeout` 5 s by default | only the address differs between environments; paths come from the contract |
| Error body | our `ErrorResponse`, not transaction-service's RFC 9457 problem details; the `error` code is kept | one error format on every individuals-api endpoint |
| Retries | individuals-api never repeats a call | confirm is idempotent by `requestId`, the client repeats it |

### What the client is told

| Where | Client sees | transaction-service called |
|---|---|---|
| no token, expired or bad signature | **401** `AUTHENTICATION_REQUIRED` | no |
| `@Valid` | **400** `VALIDATION_ERROR` | no |
| the token has no `user_uid` (realm mapper broken) | **500** `INTERNAL_ERROR` | no |
| 2xx | the same status, our DTO | yes |
| 400 `INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `WALLET_NOT_ACTIVE`, `WALLET_TYPE_NOT_ACTIVE` | **400**, the same code — a business refusal the client must see | yes |
| 400 `VALIDATION_ERROR` (rules across two fields, e.g. transfer to the same wallet) | **400** `VALIDATION_ERROR` with their `details` — those rules live only in transaction-service | yes |
| 400 `MALFORMED_REQUEST` or an unknown code | **500** `INTERNAL_ERROR`, their body only in the log — we sent it, our bug | yes |
| 404 `WALLET_NOT_FOUND`, `WALLET_TYPE_NOT_FOUND`, `TRANSACTION_NOT_FOUND` | **404**, the same code; another user's wallet is 404 too | yes |
| 409 `REQUEST_ID_CONFLICT` | **409** | yes |
| 5xx | **503** `DEPENDENCY_UNAVAILABLE` | yes |
| no connection or no answer within 5 s | **503** `DEPENDENCY_UNAVAILABLE` (`GatewayErrors.transportFailures`) | yes |

Unlike person-service, a 400 from transaction-service is passed through: there
it is usually the client's business mistake, not ours.

**Timeout on confirm.** The operation may already be committed when the answer
is lost. The client repeats confirm with the same `requestId`;
transaction-service answers `200` with the transaction it already made and
never charges twice.

### Progress

- [x] `TransactionServiceProperties`, `transactionWebClient`, `WalletsApi` and
      `TransactionsApi` beans in `HttpClientsConfig`; `transaction-service-client`
      from Nexus; `TRANSACTION_SERVICE_URL` in compose and `.env.example`
- [x] Scenario and C4 diagrams: container, component, `sequence-wallets-transactions`
- [ ] Contract `openapi/individuals-api.yaml`: the 11 paths and their schemas
      (schema names must not repeat the client's DTO names: `TransactionClient`
      imports both)
- [ ] Controllers, services, `TransactionClient`, new `ErrorCode` values, the
      `external.transaction_service.requests` timer
- [ ] Unit and integration tests (transaction-service stubbed with WireMock),
      a Postman folder
- [ ] Republish `transaction-service-client` to Nexus, full rebuild, `make verify`
- [ ] Grafana legends read as `create_wallet`, not as Java names (`createWallet`)
- [ ] Open: no endpoint lists wallet types, their uids come only from the
      `V003__seed_wallet_types.sql` seed
