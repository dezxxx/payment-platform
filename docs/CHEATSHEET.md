# Cheat sheet

One page with every address, port, credential and command used in this project,
so nothing here has to be looked up in the course handout or in `CONTEXT.md`
again. Values come from `.env` (copy of `.env.example`); this file only records
what they are and what they are for.

Russian mirror: [`CHEATSHEET.ru.md`](CHEATSHEET.ru.md). The classes are a
different sheet: [`CLASSES.md`](CLASSES.md).

---

## 1. Ports

Host port is what you type in a browser or in `curl`. Container port is what the
service listens on inside the compose network — that is the one used in
`application-docker.yml` and `prometheus.yml`, never the host port.

| Service | Host | Container | Open this | What it is |
|---|---|---|---|---|
| keycloak | 8080 | 8080 | http://localhost:8080 | auth server, admin console |
| individuals-api | 8081 | 8081 | http://localhost:8081 | our service |
| person-service | 8082 | 8082 | — | module 2, not running yet |
| nexus | 8083 | 8083 | http://localhost:8083 | private Maven repo, not running yet |
| keycloak-postgres | 5433 | 5432 | — | Keycloak's database |
| person-postgres | 5434 | 5432 | — | person-service database (migrations only) |
| prometheus | 9090 | 9090 | http://localhost:9090 | metrics, scrapes `individuals-api:8081` |
| tempo | 3200 | 3200 | http://localhost:3200 | trace storage, queried by Grafana |
| tempo OTLP | 4318 | 4318 | — | where the app pushes traces |
| loki | 3100 | 3100 | http://localhost:3100 | log store, queried through Grafana |
| alloy | none | none | — | reads container logs, pushes them to Loki |
| grafana | 3000 | 3000 | http://localhost:3000 | dashboards |

Why 8083 for Nexus: Nexus normally defaults to 8081, but the handout fixes 8081
for individuals-api, so Nexus is the one that moves. URLs live in
`gradle.properties`.

## 2. Credentials

All of these are local-only defaults from `.env.example`. Real values live in
`.env`, which is never committed.

| Where | User | Password |
|---|---|---|
| Keycloak admin console (master realm) | `admin` | `admin` |
| Grafana | `admin` | `admin` |
| keycloak-postgres | `keycloak` | `keycloak` (db `keycloak`) |
| person-postgres | `person` | `person` (db `person`) |
| Keycloak client `individuals-api` | client secret | `KEYCLOAK_CLIENT_SECRET` |

Realm: `payment-platform`. Client id: `individuals-api` (confidential, service
account on, direct access grants on, standard flow off).

## 3. Our endpoints

Base: `http://localhost:8081`

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| POST | `/v1/auth/registration` | none | 201 tokens | 400 validation, 409 email taken, 503 dependency |
| POST | `/v1/auth/login` | none | 200 tokens | 400 validation, 401 bad credentials, 503 |
| POST | `/v1/auth/refresh-token` | none | 200 tokens | 400 validation, 401 expired/invalid refresh, 503 |
| GET | `/v1/auth/me` | `Bearer <access>` | 200 user | 401 missing/invalid token |

Infrastructure, all open:

| Path | What |
|---|---|
| `/actuator/health` | liveness/readiness probes |
| `/actuator/info` | build info |
| `/actuator/prometheus` | metrics, scraped every 15s |
| `/swagger-ui.html` | contract UI |
| `/v3/api-docs` | contract JSON |

Contract source of truth: `individuals-api/openapi/individuals-api.yaml`.
Everything else (`AuthApi`, the six DTOs) is generated from it.

## 4. HTTP status codes used here

Only the ones this service can answer with. Every one of them is a constant in
`ErrorCode`, except 200/201 which are successes.

| Code | Name | Means | Whose fault | Our constant |
|---|---|---|---|---|
| 200 | OK | the call worked | - | login, refresh-token, me |
| 201 | Created | worked and made something new | - | registration |
| 400 | Bad Request | the request itself is wrong - bad email, missing field, broken JSON | caller | `VALIDATION_ERROR` |
| 401 | Unauthorized | "I do not know who you are" - no token, expired token, wrong password | caller | `INVALID_CREDENTIALS` |
| 403 | Forbidden | "I know who you are and you may not" - valid token, missing role | caller | `ACCESS_DENIED` |
| 404 | Not Found | no such path, or no such user | caller | `NOT_FOUND` |
| 405 | Method Not Allowed | the path exists, the HTTP method does not | caller | `METHOD_NOT_ALLOWED` |
| 409 | Conflict | it already exists - the email is taken | caller | `USER_ALREADY_EXISTS` |
| 415 | Unsupported Media Type | wrong Content-Type, e.g. text/plain instead of application/json | caller | `UNSUPPORTED_MEDIA_TYPE` |
| 500 | Internal Server Error | we broke - a bug, a misconfigured client secret | ours | `INTERNAL_ERROR` |
| 503 | Service Unavailable | a dependency is down - Keycloak unreachable | ours | `DEPENDENCY_UNAVAILABLE` |

The families, when a code is not in the table:

- **2xx** worked
- **3xx** go somewhere else (we never answer with these)
- **4xx** the caller is wrong - the same request will fail again unchanged
- **5xx** we are wrong - the same request might work later

That distinction is the whole reason 401 is not 400 and 503 is not 500: it tells
the caller whether retrying is pointless or worth it.

The pair mixed up most often: **401 is "who are you?", 403 is "not for you"**.

## 5. Keycloak endpoints we call

`REALM` = `payment-platform`.

| Purpose | Call |
|---|---|
| service-account token | `POST /realms/REALM/protocol/openid-connect/token` — `grant_type=client_credentials` |
| user login | `POST /realms/REALM/protocol/openid-connect/token` — `grant_type=password` |
| refresh | `POST /realms/REALM/protocol/openid-connect/token` — `grant_type=refresh_token` |
| create user | `POST /admin/realms/REALM/users` — body carries `user_uid`, **no password**; answers 201 with an empty body, the new id is only in the `Location` header |
| set the password | `PUT /admin/realms/REALM/users/{id}/reset-password` — `temporary: false`, or the next login fails with 400 `invalid_grant` |
| delete user | `DELETE /admin/realms/REALM/users/{id}` — compensation only, when the password could not be set |
| read user | `GET /admin/realms/REALM/users/{id}` — we read one field, `createdTimestamp` |
| JWKS / issuer | `GET /realms/REALM/.well-known/openid-configuration` |

How Keycloak's errors map to ours: `400 invalid_grant` "Invalid user
credentials" → our 401; `400 invalid_grant` "Account is not fully set up" →
also 401, and it means a temporary password was set; `409` "User exists with
same email" → our 409.

## 6. Commands

```bash
# whole stack
docker compose up -d
docker compose ps
docker compose logs -f keycloak
docker compose down            # keep volumes
docker compose down -v         # wipe volumes - realm is re-imported on next up

# just what individuals-api needs when running from the IDE
docker compose up -d keycloak

# run the app from the terminal
./gradlew :individuals-api:bootRun

# build and test
./gradlew :individuals-api:compileJava
./gradlew build
```

Smoke checks. **In PowerShell always write `curl.exe`, never `curl`** — bare
`curl` is an alias for `Invoke-WebRequest`, which has no `-i` flag and throws an
exception on 4xx instead of printing the response body, which is exactly what we
need to see here.

```bash
# app is up
curl -i http://localhost:8081/actuator/health

# no token -> must be 401 with OUR json body (timestamp, path, status, error, message, traceId)
curl -i http://localhost:8081/v1/auth/me

# keycloak is up and the realm imported
curl -s http://localhost:8080/realms/payment-platform/.well-known/openid-configuration | head -c 300

# log in a user directly against keycloak (bypasses our api)
curl -s -X POST http://localhost:8080/realms/payment-platform/protocol/openid-connect/token \
  -d grant_type=password -d client_id=individuals-api \
  -d client_secret=CHANGE_ME -d username=EMAIL -d password=PASSWORD
```

Decode an access token without any tool (PowerShell):

```powershell
$t = "PASTE.TOKEN.HERE".Split(".")[1]
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($t.PadRight($t.Length + (4 - $t.Length % 4) % 4, "=")))
```

## 7. Same value in two files

Change one side and the other silently breaks. Full table in `CONTEXT.md` §
"Cross-file consistency"; the ones that bite most often:

| Value | Lives in | Must match |
|---|---|---|
| app port | `application.yml` `server.port` | compose `8081:8081`, `prometheus.yml` target |
| realm name | `.env` `KEYCLOAK_REALM` | `realm-export.json` `realm` |
| client id | `.env` `KEYCLOAK_CLIENT_ID` | `realm-export.json` `clientId` |
| client secret | `.env` `KEYCLOAK_CLIENT_SECRET` | `realm-export.json` `secret` |
| tempo endpoint | `application-docker.yml` `tempo:4318/v1/traces` | `tempo.yml` OTLP receiver |
