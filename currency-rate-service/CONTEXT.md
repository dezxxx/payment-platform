# CONTEXT - currency-rate-service

Module 4. Shared rules, ports and how the platform is started: [`CONTEXT.md`](../CONTEXT.md).

---

## 1. What it is

Exchange rates for transfers between currencies. Once an hour it takes the rates from an external provider, computes
every pair of the active currencies and stores them with a validity period; on request it returns the rate of a pair
valid at a moment. It knows nothing about money, wallets or users.

| | |
|---|---|
| Called by | individuals-api only, with its service token (realm role `SERVICE`) |
| Calls | the rate provider (Open Exchange Rates) only; never another platform service |
| Owns | its own database `currency` (container `currency-postgres`) |
| Stack | Java 25, Spring Boot 4.1, Spring MVC (blocking), Spring Data JPA, Flyway, ShedLock, Resilience4j retry, Gradle per module |
| Package | `com.dezxxx.currency`: `client`, `config`, `entity`, `exception`, `mapper`, `metrics`, `repository`, `rest`, `scheduler`, `service` |
| Port | 8088; database 5437 on the host |
| Diagrams | `diagrams-c4/`: `container`, `component`, `er-tables`, `sequence-rates-update`, `sequence-rate-get`, `sequence-transfer-between-currencies`, `circuit-breaker` (the breaker lives in individuals-api) |

Who computes what in a transfer UAH → EUR: currency-rate-service gives the rate only; individuals-api orders the calls
(wallet currencies → rates → `transfer/init`) and computes nothing; transaction-service computes the money (minimum
1 USD, fee, `targetAmount`, balance). See `transaction-service/CONTEXT.md` §2.

---

## 2. Database

Flyway, `ddl-auto: validate`, tables in `public`. Before a stand exists the migrations are edited in place and the
database is reset (`make reset-db`).

**V001 `create_tables`**

| Table | Columns | Rules |
|---|---|---|
| `currencies` | `id BIGSERIAL`, `code VARCHAR(3)`, `iso_code CHAR(3)`, `description VARCHAR(64)`, `symbol VARCHAR(8)`, `active BOOLEAN DEFAULT TRUE`, `created_at`, `modified_at` | `code` unique, `^[A-Z]{3}$`; `iso_code` unique, `^[0-9]{3}$` |
| `rate_providers` | `provider_code VARCHAR(3)` PK, `provider_name VARCHAR(28)`, `description`, `priority INTEGER`, `active`, `created_at`, `modified_at` | `provider_name` unique, `priority` unique |
| `conversion_rates` | `id BIGSERIAL`, `source_code`, `destination_code` (FK `currencies.code`), `rate NUMERIC(19,8)`, `rate_begin_time`, `rate_end_time`, `provider_code` (FK), `created_at` | unique (`source_code`, `destination_code`, `rate_begin_time`); `rate > 0`; `source_code <> destination_code`; `rate_end_time > rate_begin_time` |
| `shedlock` | `name` PK, `lock_until`, `locked_at`, `locked_by` | the ShedLock table, no entity: the library writes it through JDBC |

The constraints hold even with one provider: they are the last guard against a bug in the update (a zero rate, a
pair of one currency, a rate that ends before it starts).

**V002 `seed_currencies_and_rate_providers`**: currencies USD 840, EUR 978, UAH 980, ILS 376 (all active);
provider `OXR` "Open Exchange Rates", priority 1. No admin API: currencies and providers change only by migration.
The wallet types of transaction-service (V003 there) cover the same four currencies; their uid ends with the ISO number.

**Time.** Every `TIMESTAMP` column holds UTC without a zone: `hibernate.jdbc.time_zone: UTC` and
`preferred_instant_jdbc_type: TIMESTAMP`, entities use `Instant`. A `java.sql.Timestamp` passed through JDBC is shifted
by the driver to the machine's zone - in tests write `LocalDateTime.ofInstant(instant, UTC)` and compare with
`timezone('utc', now())`, not `now()`.

**Entities** (`CurrencyEntity`, `RateProviderEntity`, `ConversionRateEntity`): a currency in a rate is a `String` code,
not a reference - the update needs only the codes for 12 pairs, the FK keeps the link in the database. `rate` is
`BigDecimal`. A rate is never changed, every hour adds rows, so `ConversionRateEntity` has no `modifiedAt`.
`iso_code CHAR(3)` needs `@JdbcTypeCode(SqlTypes.CHAR)`, otherwise `validate` fails on `bpchar` vs `varchar`.

---

## 3. Hourly update

`RateUpdateScheduler.updateRates()` → `ExchangeRateService.updateRates()`.

| Setting (`currency.rates.*`) | Value |
|---|---|
| `update-interval` | `1h`, `fixedRate` |
| `initial-delay` | `0s`: the first update right after the start (tests set it far away and call the bean) |
| `lifetime` | `3h`: a rate written at 12:00 is valid until 15:00; two missed updates still leave a valid rate |
| `lock-at-most-for` / `lock-at-least-for` | `59m` / `59m` |
| `transaction-timeout` | `30s`, the write |

Steps:

1. Active currencies from `currencies` (USD, EUR, UAH, ILS).
2. Providers from `rate_providers`, active, by `priority`. Each in turn until one is valid: every active currency is in
   the answer and every rate `> 0`. An exception or an incomplete answer → WARN, the next provider.
3. OXR: `GET {OXR_URL}/latest.json?symbols=USD,EUR,UAH,ILS`, the App ID in the header `Authorization: Token …`
   (a query parameter would land in traces and access logs). Answer `OxrLatestResponse(timestamp, base, rates)`,
   base USD: "1 USD = 0.90715 EUR, 45.477786 UAH, 3.216745 ILS".
4. Pairs: every ordered pair of different active currencies, 4 currencies → 12 rows.
   `source → destination = (USD → destination) / (USD → source)`, `MathContext.DECIMAL64`, then scale 8 HALF_UP.
   Example: UAH → EUR = 0.90715 / 45.477786 = 0.01994710. The base currency is not special: USD → X is X / 1.
5. One write: `TransactionTemplate` with the 30 s timeout, `saveAll` of the 12 rows, `rate_begin_time` = now,
   `rate_end_time` = now + 3 h, `provider_code` of the provider used.
6. Success → `rate.updates{result=success}`, INFO. No valid provider or any exception (the write included) →
   `rate.updates{result=failure}`, ERROR "the previous rates stay valid until their rate_end_time"; nothing is thrown to
   the scheduler, the old rows keep answering.

Decisions:

| Decision | Why | Rejected |
|---|---|---|
| Rates exactly from the provider | the service reports the market rate; nobody edits rates by hand | a table of correction coefficients (`rate_corrections`): nothing needs it, it only adds a hand-made number to a rate |
| Pairs computed here, once an hour | one number for every caller; history: `GET /rates?timestamp=12:30` returns the rate that was valid at 12:30; a rate is a property of the rate, not of a transfer (SRP) | transaction-service computing the pair per transfer: the same division a thousand times, no stored history, two services rounding apart |
| One request to the provider, from USD | one OXR call returns every rate from one base, USD, and covers every pair | a call per pair |
| `@Transactional(propagation = NOT_SUPPORTED)` on `updateRates()` | no database connection is held while the provider is awaited (up to ≈ 46 s, the pool has 10); only the write is atomic: all 12 rows or none. Rule: a network call to another system runs outside a database transaction. Works because the scheduler calls it through the proxy; the write inside the class therefore goes through `TransactionTemplate`, not a self-call | one transaction over the whole update: the class-level `readOnly = true` would also block the write |
| Provider client per `provider_code` | `ExternalRateProviderClient.fetchUsdRates(providerCode, codes)`; a code without a client → `INTERNAL_ERROR` and the next provider | - |

**Provider client** (`RateProviderClientConfig`, `currency.provider.*`): `RestClient` + `@HttpExchange` interface
`OxrApi`, JDK `HttpClient`. `connect-timeout: 10s`; `response-timeout: 5s` set as the read timeout of
`JdkClientHttpRequestFactory`, which covers the **whole body**, not only the wait for the headers (a server that sends
headers and stalls the body must still time out). Retry `rateProvider` (Resilience4j): 3 attempts, 500 ms apart,
retries `RestClientException` (5xx, timeouts, no connection; a body-read timeout is a plain `RestClientException`),
ignores `HttpClientErrorException` (4xx: a wrong key is not fixed by a retry).

**Time budget against the lock.** One provider at most: 3 × (10 s + 5 s) + 2 × 0.5 s ≈ 46 s; the write 30 s.
N providers × 46 s + 30 s stays far below `lockAtMostFor` 59 min, so the lock never expires during a running update.

### ShedLock

ShedLock is a lock, not a scheduler: Spring `@Scheduled` fires the method on every instance, `@SchedulerLock(name =
"updateRates")` lets only one of them run it.

- The lock is a row in the database table `shedlock` (`name = 'updateRates'`): an instance that sets `lock_until` into
  the future runs the update, the others see the row taken and skip.
- `JdbcTemplateLockProvider.usingDbTime()`: every instance compares `lock_until` with the database clock, not its own;
  `lock_until` is stored in UTC (`timezone('utc', CURRENT_TIMESTAMP)`).
- `lockAtLeastFor 59m`: the row stays taken for 59 min even after a fast update, so a second instance whose clock or
  start differs does not run the same hour again. `lockAtMostFor 59m`: if the instance dies mid-update, the row frees
  itself before the next hour.
- **Never delete a row of `shedlock`.** ShedLock caches in memory that the row exists and afterwards only UPDATEs it;
  a deleted row is never recreated and every later update is silently skipped. Free the lock with
  `UPDATE shedlock SET lock_until = timezone('utc', now())`.

---

## 4. Reading a rate

`GET /api/v1/rates?from=UAH&to=EUR[&timestamp=2026-11-14T12:30:00Z]`

1. `from`, `to`: `^[A-Z]{3}$` (contract, `@Valid`); `from = to` → `400 VALIDATION_ERROR`, checked in the controller
   (one simple check of a small request, no validation class).
2. `timestamp` ISO 8601, absent → now (`Clock` bean).
3. `ConversionRateRepository.findValid`: `rate_begin_time <= moment < rate_end_time`, newest `rate_begin_time` first,
   `limit 1`. A rate lives 3 h and a new one comes every hour, so up to three rows are valid at once - the newest wins.
   `rate_end_time` itself is no longer valid.
4. Found → `200 RateResponse`; none → `404 RATE_NOT_FOUND` with the pair and the moment in `details`.

Unknown currencies need no separate check: there is simply no rate → 404.

---

## 5. Contract

`openapi/currency-rate-service.yaml`, contract-first: the server interface `CurrencyRateApi` (implemented by
`CurrencyRateRestControllerV1`) and the client `currency-rate-service-client` (`spring-http-interface`, reactive, for
individuals-api) are generated from it; classes carry the `Dto` suffix (`modelNameSuffix`). `openApiValidate` runs
before generation and in `check`. Swagger UI shows the yaml itself (`springdoc.swagger-ui.url`).

| Operation | Answer |
|---|---|
| `GET /api/v1/rates` (`getRate`) | `RateResponse`: `sourceCode`, `destinationCode`, `rate` (8 digits, never rounded further), `rateTimestamp` (= `rate_begin_time`, UTC), `providerCode`; 400, 401, 403, 404, 500 |
| `GET /api/v1/currencies` (`getCurrencies`) | every currency, active or not, by code: `code`, `isoCode`, `description`, `symbol`, `active` |
| `GET /api/v1/rate-providers` (`getRateProviders`) | every provider by priority: `providerCode`, `providerName`, `description`, `priority`, `active` |
| `/actuator/health` | instead of a `/health` endpoint of our own: Docker's healthcheck and Prometheus already use the actuator |

The client artifact `com.dezxxx:currency-rate-service-client` goes to Nexus (`make currency-publish`); its classes are
`com.dezxxx.currency.client.model.*Dto` - a different class from the server's `com.dezxxx.currency.api.model.*Dto`, made
from the same yaml, so the JSON fits both.

---

## 6. Security and errors

- Resource server: the token is checked against Keycloak's keys (`jwk-set-uri` from `KEYCLOAK_URL`,
  `KEYCLOAK_REALM`; not `issuer-uri` - the `iss` of a token issued through `localhost` differs from the one inside
  Docker). Realm roles from `realm_access.roles` → `ROLE_*`.
- Every endpoint except the actuator (`health`, `info`, `prometheus`, `metrics`), `/v3/api-docs`, `/openapi/**` and
  Swagger UI needs the role `SERVICE`. No token or a foreign signature → `401 AUTHENTICATION_REQUIRED`, a user token →
  `403 ACCESS_DENIED`. Why closed: the port is published, and the service is part of the platform, not a public API.
- 401 and 403 go through `GlobalExceptionHandler` (the entry point and the access-denied handler hand the exception to
  `HandlerExceptionResolver`), so they have the same body as every other error. Without that Spring answers an empty
  401 and the client gets no error code.
- Stateless, no CSRF, no form login, no HTTP Basic.
- `ErrorCode`: `VALIDATION_ERROR` 400, `AUTHENTICATION_REQUIRED` 401, `ACCESS_DENIED` 403, `RATE_NOT_FOUND` 404,
  `NOT_FOUND` 404 (unknown path), `METHOD_NOT_ALLOWED` 405, `INTERNAL_ERROR` 500. Body: RFC 9457
  `ProblemDetail` + `timestamp`, `error`, `traceId`, `details`, as in the other services.
- `ErrorCode`, `GlobalExceptionHandler` and `SecurityConfig` are this service's own copies on purpose: each service owns
  its error codes and its security settings. Rejected: a shared `platform-error` jar - one change would touch every
  service at once, and the settings of different applications may diverge.

---

## 7. Configuration

| Key | Source | Value |
|---|---|---|
| `server.port` | yml | 8088 |
| `spring.datasource.*` | `CURRENCY_DB_HOST`, `CURRENCY_DB_PORT`, `CURRENCY_DB_NAME`, `CURRENCY_DB_USER`, `CURRENCY_DB_PASSWORD` | localhost:5437/currency outside Docker; compose sets `currency-postgres:5432` |
| `currency.provider.oxr.app-id` | `OXR_APP_ID` (`.env`, never in git; a free key at openexchangerates.org/signup/free) | empty default: the service starts, every update fails with 401 and is counted |
| `currency.provider.oxr.url` | `OXR_URL` | `https://openexchangerates.org/api`; tests point it to the stub |
| `currency.provider.connect-timeout` / `response-timeout` | yml | 10s / 5s |
| `currency.rates.*` | yml | §3 |
| `currency.metrics.refresh-interval` | yml | 30s |
| `resilience4j.retry.instances.rateProvider` | yml | §3 |
| `KEYCLOAK_URL`, `KEYCLOAK_REALM` | compose | `jwk-set-uri` |
| `OTLP_TRACES_ENDPOINT` | compose | `alloy:4318/v1/traces` |

Properties are records: `RatesProperties` (`currency.rates`), `RateProviderProperties` (`currency.provider`).
Logs: ECS JSON on the console (`logging.structured.format.console: ecs`), as every service. Tracing sampled at 1.0.

Docker: `Dockerfile` builds the jar in the image (JDK stage `bootJar`, JRE stage runs it as user `app`, its own Gradle
cache id). Compose: `currency-rate-service` waits for `currency-postgres` healthy; healthcheck on `/actuator/health`.
`gradlew` must be `100755` in git.

---

## 8. Metrics, alerts, dashboard

`RateMetrics`:

| Metric | Meaning |
|---|---|
| `rate_updates_total{result=success\|failure}` | hourly updates by result |
| `rate_update_duration_seconds` | one update: provider calls and the write |
| `rate_newest_age_seconds` | how long ago the newest rate became valid; 0 when there is none |
| `rate_valid_pairs` | pairs with a rate valid now; 0 means every `GET /rates` answers 404 |
| `rate_operations_seconds{operation=getRate}` | `@Observed` on the read |

The two gauges are filled by `refresh()` every 30 s from the table; a scrape never queries the database.

Alerts (`infra/prometheus/alerts.yml`): `CurrencyRatesTooOld` (newest rate > 2 h for 5 min - two updates missed, one
hour left before 404), `CurrencyRatesMissing` (`rate_valid_pairs == 0` for 5 min), `CurrencyRateUpdateFailed` (any
failure in the last hour). Prometheus job `currency-rate-service`, target `currency-rate-service:8088`.
Dashboard `infra/grafana/dashboards/currency-rate-service.json`.

---

## 9. Tests

JUnit 5 + Mockito, given / when / then. `make currency-test` (unit), `make currency-it` (integration, Testcontainers;
the stack must be down - memory).

| Class | Checks |
|---|---|
| `ExchangeRateServiceUpdateTest` | 12 pairs through USD valid 3 h; a provider missing a currency, giving 0 or failing → the next one; no valid provider → nothing saved, failure counted; a failed write counted, nothing thrown |
| `ExchangeRateServiceTest` | read now / at a timestamp with an offset (looked up in UTC), `RATE_NOT_FOUND`, lists |
| `ExternalRateProviderClientTest`, `CurrencyRateMapperTest`, `RateMetricsTest`, `CurrencyRateRestControllerV1Test`, `RateUpdateSchedulerTest` | only our currencies asked, unknown provider; mapping keeps every digit; gauges and counters; `from = to`; the scheduler calls the service |
| `RateUpdateIT` | against `OxrStub`: 12 pairs, the key in the header; ShedLock skips a second run; 500 twice then success; 500 three times → nothing saved; 401 → one call; inactive ILS → 6 pairs; an incomplete answer → nothing saved; a body held past the timeout → three attempts; old rates survive a failed update; update → `GET` ILS→UAH; the gauges |
| `CurrencyRateRestIT` | 200, newest valid, 404, the end boundary; 400 (`from = to`, lowercase, missing, bad timestamp); 401 / 403; lists and an open health; 405, 404 |

Support (`integration/support`): `IntegrationTest` (Postgres 18.2 in Testcontainers, properties through
`@DynamicPropertySource`, before each test: rates deleted, the ShedLock row freed by `UPDATE`, currencies active, the
stub reset), `OxrStub` (JDK `HttpServer` with a queue of answers per test, records the requests), `TestTokens` (two Nimbus
RSA keys; a `JwtDecoder` bean that trusts only the first replaces Keycloak; a service token, a user token, a token of
the other key). The update is
started by calling the scheduler bean, not by waiting an hour.

---

## 10. Done and next

### Done

- [x] Module, Dockerfile, compose with `currency-postgres`, `.env.example`, Makefile block `currency-*`, Prometheus job
- [x] V001, V002; entities; repositories
- [x] Hourly update through USD, ShedLock, retry, timeouts, `NOT_SUPPORTED`; reading; lists
- [x] Security with 401 / 403 in the common body; own `ErrorCode` and `GlobalExceptionHandler`
- [x] Metrics, three alerts, the dashboard
- [x] Unit and integration tests, all green
- [x] C4 diagrams in `diagrams-c4/`

### Next

- [ ] `currency-rate-service-client` to Nexus; individuals-api: the client bean with the service token,
      `CurrencyRateServiceClient` (404 → 422 `RATE_NOT_FOUND`; 5xx, no answer, open breaker → 503
      `DEPENDENCY_UNAVAILABLE`; other 4xx → 500), the circuit breaker
- [ ] Postman collection and README
- [ ] Live check on the stack: rates appear after the start, metrics and alerts in Grafana
