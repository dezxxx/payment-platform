# Walking through module 1

The script for showing the module whole: from a registration to what the
meters, the logs and the traces said about it. Fifteen minutes, done by hand
rather than read off someone else's screenshot.

Every command here was run for real. If something answers differently, that is
a discrepancy worth chasing, not a difference of setup.

Russian mirror: [`DEMO.ru.md`](DEMO.ru.md). Addresses, credentials and ports
live in [`CHEATSHEET.md`](CHEATSHEET.md).

---

## 0. Before you start

**In PowerShell always write `curl.exe`, never `curl`.** Bare `curl` there is an
alias for `Invoke-WebRequest`, which does not understand `-X`, `-H` or `-d` and
throws on a 4xx instead of printing the body. The body is exactly what you came
to see.

### Why a stand-in is needed

Registration is the module's main scenario, and its first step calls
**person-service** - which module 1 does not contain. Only its contract and its
migrations exist. Without it, registration honestly answers **503 (Service
Unavailable)** and there is nothing left: no successful meters, no "Registered"
line in the logs, no trace through the chain.

So a stub takes its place for the walkthrough. It answers the one operation
`person-service/openapi/person-service.yaml` describes and is **not part of the
deliverable** - it is a demonstration aid.

### Turning it on

1. Uncomment this line in `.env`:

   ```
   PERSON_SERVICE_URL=http://host.docker.internal:8082
   ```

   The application runs in Docker and the stub runs on the host;
   `host.docker.internal` is how a container reaches your machine. The
   application stays containerised on purpose: run from the IDE, its logs never
   reach Alloy and Loki has nothing to show.

2. In a **separate** terminal, start it and leave it running:

   ```bash
   node docs/demo/person-service-stub.js
   ```

   It prints every call it receives, which is how you see that the service
   really went there.

---

## 1. Bring the stack up

```bash
docker compose up -d --build
docker compose ps
```

Ten services. Keycloak imports the realm on a cold start and refuses everything
for the first half-minute - that is normal, and it is part of the picture: the
application survives a dependency being unavailable instead of dying with it.

Wait until both answer:

```bash
curl.exe -s http://localhost:8081/actuator/health
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8080/realms/payment-platform/.well-known/openid-configuration
```

`{"status":"UP"}` and `200`.

> **`Up` versus `(healthy)` in `docker compose ps`.** `Up` means the process
> started; `(healthy)` means a readiness check passed. A database starts in a
> second but accepts no connections for several more: to Docker it is already
> `Up`, to Keycloak it is not. Hence the `healthcheck` on the databases in
> `docker-compose.yml` and `depends_on: condition: service_healthy` on Keycloak.

---

## 2. Registration

```bash
curl.exe -X POST http://localhost:8081/api/v1/auth/registration ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"demo1@example.com\",\"password\":\"Str0ngPass!\",\"confirmPassword\":\"Str0ngPass!\",\"firstName\":\"Ivan\",\"lastName\":\"Ivanov\"}"
```

**201 (Created)** and a token pair:

```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<jwt>",
  "expiresIn": 300,
  "tokenType": "Bearer",
  "userUid": "85e3d59e-e480-4b29-96a1-2a75d482c955"
}
```

**What to say out loud.** person-service issued that `userUid`, not us. The same
value went into Keycloak as an account attribute and reached the token through
the realm's mapper. One identifier, three places, no drift - which is what the
module is for. The stub's window shows `-> 201 85e3d59e…` at the same moment.

No separate login was needed: registration logs the new user in, so the client
never makes a second call.

### The same address twice

Run the same command again. **409 (Conflict)**, `USER_ALREADY_EXISTS`.

Where it happened is the point: person-service refused **first**, before
Keycloak, so there is nothing to undo and the caller gets the precise answer
rather than our internal failure. That is exactly why person-service is the
first step of the scenario.

---

## 3. Login, refresh, who am I

```bash
curl.exe -X POST http://localhost:8081/api/v1/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"demo1@example.com\",\"password\":\"Str0ngPass!\"}"
```

Same response shape. Copy the `accessToken` and ask who you are:

```bash
curl.exe http://localhost:8081/api/v1/auth/me -H "Authorization: Bearer <accessToken>"
```

```json
{
  "userUid": "85e3d59e-…",
  "keycloakUserId": "a073ad90-…",
  "email": "demo1@example.com",
  "firstName": "Ivan",
  "lastName": "Ivanov",
  "emailVerified": false,
  "roles": ["USER"],
  "registeredAt": "2026-09-20T09:47:26.123Z"
}
```

**Two identifiers side by side is not duplication.** `userUid` is the platform's;
`keycloakUserId` is the token's `sub`, a technical link. The business layer uses
only the first.

**`roles: ["USER"]`** - our service grants that role at registration; Keycloak
does not assign it on its own. Keycloak's internal roles (`offline_access`,
`uma_authorization`) are filtered out: they say what an account may do inside
Keycloak, and the client is asking a business question.

**`registeredAt`** is the one field no claim carries. `/me` spends one call on
Keycloak's Admin API for it; the other seven come from the already-verified
token and cost nothing.

Refresh:

```bash
curl.exe -X POST http://localhost:8081/api/v1/auth/refresh-token ^
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"<refreshToken>\"}"
```

---

## 4. The failures, all in one shape

```bash
# confirmation does not match -> 400
curl.exe -X POST http://localhost:8081/api/v1/auth/registration ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"x@y.io\",\"password\":\"Str0ngPass!\",\"confirmPassword\":\"Other1!\",\"firstName\":\"I\",\"lastName\":\"I\"}"

# wrong password -> 401 INVALID_CREDENTIALS
curl.exe -X POST http://localhost:8081/api/v1/auth/login ^
  -H "Content-Type: application/json" -d "{\"email\":\"demo1@example.com\",\"password\":\"Wrong1!\"}"

# no token -> 401 AUTHENTICATION_REQUIRED
curl.exe http://localhost:8081/api/v1/auth/me

# a path that does not exist -> also 401 AUTHENTICATION_REQUIRED
curl.exe http://localhost:8081/api/v1/auth/nothing-here
```

All four answer in the same shape - `timestamp`, `path`, `status`, `error`,
`message`, `traceId`, `details`.

**The last two share a status and carry different codes**, deliberately: no
password was read on a request that carried none, so saying "email or password
is incorrect" would send the reader looking in the wrong place.

**An unknown path answers 401 rather than 404**, also deliberately: telling an
unauthenticated caller which paths exist maps out the API for anyone who asks.

---

## 5. What the meters recorded

```bash
curl.exe -s http://localhost:8081/actuator/prometheus | findstr /B "auth_ external_"
```

After the run above:

```
auth_registration_total           2.0     <- attempts that reached the service
auth_registration_success_total   1.0
auth_registration_failure_total   1.0     <- the duplicate address
auth_login_total                  2.0
auth_login_failure_total          1.0
auth_refresh_total                1.0
external_keycloak_requests_seconds_count        …
external_person_service_requests_seconds_count  …
```

**The thing worth noticing.** Three registration requests were sent - the third
had a mismatched confirmation - and the counter says **two**. The one rejected
with **400** never reached the service; validation on the controller stopped it.
So the counter measures the health of the orchestration, not the quality of the
input. Placed on the controller it would report a 33% failure rate on a service
working perfectly.

### In Prometheus

http://localhost:9090/graph, the **Graph** tab:

```promql
rate(auth_registration_total[5m])
rate(auth_registration_success_total[5m]) / rate(auth_registration_total[5m])
rate(external_keycloak_requests_seconds_sum[5m]) / rate(external_keycloak_requests_seconds_count[5m])
```

Always `rate`, never the raw value: a counter resets on restart, and `rate`
knows that while a raw value would draw a cliff into the negative.

### In Grafana

http://localhost:3000, `admin` / `admin` (it offers a password change - **Skip**).
**Dashboards → individuals-api**. Set the range top right to **Last 15 minutes**
or the burst is lost in the average.

| Panel | Shows |
|---|---|
| Registration outcome | attempts / successes / failures |
| Logins and refreshes | logins, refusals, token refreshes |
| Outbound calls | Keycloak's call duration against person-service's |
| Since the application started | the same counters as plain numbers |

The last one exists for exactly this kind of hands-on run: after a dozen
requests a five-minute rate has averaged the burst away, while the stat panel
reads straight.

---

## 6. Logs: Loki

**Grafana → Explore** (the compass) → source **Loki** → the **Code** tab.

| Query | Shows |
|---|---|
| `{service="individuals-api"}` | everything the service wrote |
| `{service="individuals-api"} \|= "Registered"` | the successful registration |
| `{service="individuals-api", level=~"ERROR\|WARN"}` | only the problems |
| `{service="individuals-api"} \|= "409"` | one specific refusal |

Expand any line:

```json
{
  "@timestamp": "…",
  "log": { "level": "INFO", "logger": "com.dezxxx.individuals.service.RegistrationService" },
  "service": { "name": "individuals-api" },
  "message": "Registered 85e3d59e-e480-4b29-96a1-2a75d482c955",
  "traceId": "75d91d98fd18a312a2462909ee9d215b",
  "spanId": "…",
  "http.method": "POST",
  "http.path": "/api/v1/auth/registration",
  "http.status": "201",
  "user_uid": "85e3d59e-…"
}
```

**Those are the eight fields the module requires**, and all of them are here:
service name, `traceId`, `spanId`, path, method, status, the business error code
(on failures) and the domain `user_uid`.

Worth saying what that cost: the MDC (Mapped Diagnostic Context - the map
Logback merges into every record) is thread-local, and a reactive chain hops
threads on every outbound call. The fields travel in the Reactor Context and are
copied back into the MDC on each signal, which
`spring.reactor.context-propagation: auto` is what enables.

**Alloy** is what collects them: it reads container stdout and pushes to Loki.
That is why the application must run in Docker for this part - started from the
IDE it never reaches Loki.

---

## 7. Traces: Tempo

**Grafana → Explore** → source **Tempo** → the **TraceQL** tab:

```
{name="http post /api/v1/auth/registration"}
```

Open the longest trace. The whole chain is there:

```
6192 ms  http post /api/v1/auth/registration
   7 ms    security filterchain before
6179 ms    secured request
 311 ms      http post   <- person-service
2694 ms      http post   <- Keycloak, service-account token
 896 ms      http post   <- create the user
 495 ms      http put    <- set the password
  66 ms      http get    <- read the USER role
  35 ms      http post   <- grant it
 818 ms      http post   <- log the new user in
```

One client request, **seven** calls to external systems. Nothing else shows that.

### Tying a log line to a trace

Take the `traceId` from the "Registered" line in Loki and paste it into Tempo -
that exact trace opens. In Grafana the `TraceID` field in a log line is
clickable; the jump is wired in the datasource provisioning.

That is the correlation the module asks for: from one log line to the whole
request in two clicks.

---

## 8. Nexus

http://localhost:8083, `admin` / `admin123`.
**Browse → maven-snapshots → com/dezxxx/person-client**.

The published artifact is there: jar, sources, pom, checksums.

The point: `individuals-api` does not depend on `person-client` through
`project(":…")`. It resolves it **by Maven coordinates**, exactly as it would
resolve another team's artifact. That is what keeps the modules independent, and
every later service of the course publishes its client through the same Nexus.

That the resolution really happens there can be proven the hard way: delete the
artifact from `~/.m2` and from Gradle's cache and rebuild - the build passes,
because there is nowhere else left to get it from.

---

## 9. Tests and coverage

```bash
./gradlew build
```

Everything runs: generation from the contracts, 36 unit tests, 15 integration
tests, the JaCoCo report and the coverage threshold.

```bash
./gradlew :individuals-api:jacocoTestReport
# open individuals-api/build/reports/jacoco/test/html/index.html
```

`com.dezxxx.individuals.service` is at **100%** against the 80% floor acceptance
criterion 11 asks for. The build enforces it: drop below and `build` fails.

The integration tests start a **real** Keycloak and a **real** PostgreSQL in
containers. The realm is imported from the same `realm-export.json` that
docker-compose mounts, and the client secret is read back out of that same file -
so the two cannot drift apart.

### The Postman collection

Postman → **Import** → `postman/individuals-api.postman_collection.json` →
**Run**. Ten requests, tokens carried between them automatically. From a
terminal:

```bash
npx newman run postman/individuals-api.postman_collection.json
```

---

## 10. What you are likely to be asked

**"Why does individuals-api store nothing?"**
Because it orchestrates. The truth about the domain user belongs to
person-service and the truth about the account belongs to Keycloak. A database
of its own would be a third copy that nobody keeps in step.

**"What happens if Keycloak dies mid-registration?"**
person-service has already committed the user and there is nothing to roll it
back with - its contract offers no delete. An account left without a password is
removed by compensation, and the caller gets **503** with its own code,
`REGISTRATION_INCONSISTENT`, which is what a log query finds it by. That is
module 1's accepted limit, written down in `CONTEXT.md` rather than papered over.

**"Why does `/me` call Keycloak instead of reading the token?"**
Seven of the eight fields come from the token and cost nothing. The eighth,
`registeredAt`, is in no claim. Beyond that, a token is a snapshot from when it
was issued: it cannot know the account was disabled a minute ago, and the Admin
API can.

**"Why WebClient when the handout says RestClient?"**
The handout requires **HTTP Service Clients** - annotated interfaces with
generated proxies behind them. That is what is here, and the interface is not
hand-written but generated from the contract. `RestClient` is the synchronous
engine under such a proxy; on WebFlux that engine is `WebClient`, or the event
loop would block. Same pattern, the implementation the stack requires.

**"Show that the tests catch something"**
`RegistrationServiceTest` found a real defect: `then(login(...))` evaluates its
argument while the chain is assembled, so the gateway was called before the
password was set and even on registrations that failed. Fixed with `Mono.defer`.
Recorded in `CONTEXT.md` §8.

---

## 11. Putting it back

```bash
# Ctrl+C in the stub's window
docker compose down          # keep the volumes
docker compose down -v       # and the data: the realm is re-imported next time
```

Comment `PERSON_SERVICE_URL` out again in `.env`, or the application will keep
looking for person-service on the host once module 2 brings the real one.
