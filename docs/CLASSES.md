# individuals-api — class cheatsheet

One line per class: what it is and the single job it owns. Open it next to the
IDE. Depth lives in `CONTEXT.md`; the javadoc on each class explains *why*.

30 classes in `main`, plus the OpenAPI-generated `AuthApi` and the generated
`person-client`, neither of which is written by hand.

Russian mirror: [`CLASSES.ru.md`](CLASSES.ru.md). Ports, credentials and
commands live in [`CHEATSHEET.md`](CHEATSHEET.md) — a different sheet.

---

## The map, in one breath

```
rest/          the four endpoints, and nothing else
    ↓
service/       what happens, and what to do when it breaks
    ↓
gateway/       the only door to the outside world
    ↓
Keycloak                        person-service
```

- `config/` — what the application is assembled from.
- `error/` — what any failure looks like on the wire.
- `validation/` — the one request rule the contract cannot express itself.
- `metrics/` — every meter name, in one file.
- `util/` — knowledge about someone else's format, in one place.

---

## Root

| Class | Kind | Its one job |
|---|---|---|
| `IndividualsApiApplication` | `@SpringBootApplication` | Starts the app. Owns no data: it orchestrates person-service and Keycloak. |

## `rest` — the four endpoints

| Class | Kind | Its one job |
|---|---|---|
| `AuthController` | `@RestController implements AuthApi` | Unwraps the request, hands it to a service, wraps the answer in its status — **201 (Created)** for registration, **200 (OK)** for the rest. Declares no path of its own: methods, paths and `@Valid` are all inherited from the generated interface, so a change in the contract breaks this class at compile time. |

> Named `rest`, not `controller`: the service speaks REST and nothing else —
> no views, no consumers — so the package says which protocol it serves.
> `api` was taken by the generated code.

## `config` — what the app is assembled from

| Class | Kind | Its one job |
|---|---|---|
| `KeycloakProperties` | `record` + `@ConfigurationProperties("individuals.keycloak")` | Holds every value needed to reach Keycloak, and builds `tokenUri()` / `usersUri()` so no URL is glued together twice. A typo fails startup, not the first request. |
| `PersonServiceProperties` | `record` + `@ConfigurationProperties("individuals.person-service")` | Same, for person-service. A separate class because one properties class binds exactly one prefix. |
| `HttpClientsConfig` | `@Configuration` | The only place that knows an address. Builds `keycloakWebClient` and `personsApi` from Spring's own builder, so outgoing calls stay inside the trace, and sets the timeouts a `WebClient` does not have by default. |
| `SecurityConfig` | `@Configuration` | Declares this service a **resource server**: it never issues a token, it only verifies Keycloak's. Says which routes are public and wires the two security handlers from `error/`. |

## `error` — what a failure looks like on the wire

| Class | Kind | Its one job |
|---|---|---|
| `ErrorCode` | `enum` | The single source of truth: one constant carries the machine code, the HTTP status and the default message, so the three can never drift apart. |
| `ApiException` | `RuntimeException` | Every failure we report. One class for all cases — the case is carried by the `ErrorCode`, not by the type. |
| `ErrorResponseFactory` | `final`, static | Assembles the contract's `ErrorResponse`. The body is built here and nowhere else. |
| `GlobalExceptionHandler` | `@RestControllerAdvice` | Road one: turns anything a **handler** raises into that body. |
| `ApiAuthenticationEntryPoint` | `@Component` | Road two, part one: no token or a bad one — **401 (Unauthorized)**. Runs in the filter chain, where no advice can reach. |
| `ApiAccessDeniedHandler` | `@Component` | Road two, part two: valid token, insufficient rights — **403 (Forbidden)**. |
| `SecurityErrorWriter` | `@Component` | Serialises and writes the body for road two by hand — inside the filter chain there is no message converter to do it. |

> Two roads exist because WebFlux has no servlet `handlerExceptionResolver`:
> a filter-chain rejection cannot be delegated into an advice. They share
> `ErrorResponseFactory` so both answer in the same shape.

## `gateway` — the only door outside

Shared code sits at the level that covers everyone who uses it. That is what
the nesting means.

| Class | Package | Kind | Its one job |
|---|---|---|---|
| `GatewayErrors` | `gateway` | `final`, static | "The dependency never answered" — same handling for **every** gateway → **503 (Service Unavailable)**. |
| `KeycloakErrorTranslator` | `gateway.keycloak` | `final`, static | Translates a failed Keycloak answer into our `ErrorCode`. Shared by **both** Keycloak APIs, so `409` means "email taken" no matter which call produced it. |
| `KeycloakErrorResponse` | `gateway.keycloak` | `record` | Parses Keycloak's two different error shapes: OIDC sends `error` + `error_description`, the Admin API sends `errorMessage`. |
| `KeycloakOidcGateway` | `.oidc` | `@Component` | The **standard** side: `login`, `refresh`, and `serviceAccountToken` — the token that lets the admin gateway work at all. |
| `KeycloakTokenResponse` | `.oidc` | `record` | Keycloak's own token payload (`access_token`, `snake_case`). Never leaves the package — above it everything speaks our `TokenResponse`. |
| `KeycloakAdminGateway` | `.admin` | `@Component` | The **product-specific** side: `createUser`, `setPassword`, `deleteUser`, `findRegisteredAt`. Knows the two things Keycloak never says out loud — the new id lives only in the `Location` header, and time arrives as epoch milliseconds. |
| `KeycloakUserRequest` | `.admin` | `record` | Body of `POST /users`. Seven fields of Keycloak's fifty. No password — that is a second call. |
| `KeycloakCredential` | `.admin` | `record` | Body of `PUT /reset-password`. Carries `temporary = false`; without it the next login fails with **400 (Bad Request)**. |
| `KeycloakUserRepresentation` | `.admin` | `record`, package-private | Parses `GET /users/{id}`. One field, `createdTimestamp`. Named after Keycloak's own model so the name is searchable in their docs. |
| `PersonServiceGateway` | `gateway.person` | `@Component` | Creates the domain user and returns the `user_uid` — the identifier the whole platform uses. |
| `PersonErrorTranslator` | `gateway.person` | `final`, static | Same job as its Keycloak twin, but shorter: person-service is ours and already speaks the platform's error model. |

## `service` — what happens, and what to do when it breaks

| Class | Kind | Its one job |
|---|---|---|
| `RegistrationService` | `@Service` | The eight-step scenario: person-service → `user_uid` → Keycloak account → password → login. Owns the **compensation**: if the password fails, the half-made account is deleted. |
| `AuthenticationService` | `@Service` | Everything after registration: `login`, `refresh`, `/me`. Writes nothing. |

## `validation` — the rule the contract cannot express

| Class | Kind | Its one job |
|---|---|---|
| `PasswordsMatch` | `@interface`, `@Target(TYPE)` | Says `password` and `confirmPassword` must be the same string. On the type, not a field, because the check needs both values at once. |
| `PasswordsMatchValidator` | `ConstraintValidator` | Performs it. Stays silent when a value is missing — `@NotNull` already said that — and re-hangs the failure on `confirmPassword` so the client is told *which* field is wrong. |

> Everything else on `RegistrationRequest` is generated from the contract:
> `@NotNull`, `@Size`, `@Email`, all of it, because the build sets
> `useBeanValidation = true`. OpenAPI simply has no keyword for "this field
> equals that one", so this rule is the whole remainder.
>
> The annotation is not written on the class by hand — the class is generated.
> The schema carries `x-class-extra-annotation` and the generator stamps the
> line on verbatim, which is why it is fully qualified there: the generator
> adds no import for it. The rule therefore stays in the contract and is
> applied by the same `@Valid` that runs everything else.
>
> Nothing registers the validator. `@Constraint(validatedBy = …)` names it and
> Hibernate Validator asks Spring's factory for an instance — no `@Component`,
> no `@Bean`, and dependencies would still be injected if it ever grew any.

## `metrics` — the eight meters

| Class | Kind | Its one job |
|---|---|---|
| `AuthMetrics` | `@Component` | Registers every meter this service publishes and is the only place their names are typed. Six counters for the auth flow, two timers for outbound calls. |

> A metric name is a contract, like an `ErrorCode`: a Grafana panel and an
> alert rule are written against the string, so renaming it empties a dashboard
> in silence. One file owns all eight.
>
> Dotted names, **not** the `_total` suffix the handout's table shows —
> Prometheus appends that itself when it scrapes. Verified live:
> `auth.registration` arrives as `auth_registration_total`.
>
> The timers wrap a `Mono` inside `defer`, so the stopwatch starts on
> subscription rather than on assembly, and stop on `doFinally`, so a call that
> failed still counts — leaving failures out would flatten the timer exactly
> when something is wrong. **Facade** over `MeterRegistry`; the timing wrapper
> is a **Decorator**.

## `util`

| Class | Kind | Its one job |
|---|---|---|
| `KeycloakClaims` | `final`, static | Reads Keycloak's claims out of a verified token — `realm_access.roles` and `user_uid`. Defensive by design: a missing claim answers empty, never a 500. |

---

## Where does a new thing go?

| I am writing… | It goes to | Because |
|---|---|---|
| a URL, a timeout, a secret | `config` | one place knows addresses |
| an HTTP call to anyone | `gateway` | nothing above the gateway may hold a foreign payload |
| "their 409 means our X" | a translator in `gateway` | the translation *is* the gateway |
| an order of steps, a rollback | `service` | services orchestrate, gateways obey |
| a new failure the client sees | a constant in `ErrorCode` | status + code + message on one line |
| a request rule the contract can express | `openapi/individuals-api.yaml` | the generator writes the annotation, nobody maintains it |
| a request rule it cannot | `validation` + `x-class-extra-annotation` | the rule still lives in the contract, only its body is in Java |
| reading someone's format | `util`, or the gateway that owns it | keep the foreign format in one class |

Rules that decide the package:

1. **Shared code sits at the level that covers everyone who uses it.**
   `GatewayErrors` → all gateways. `KeycloakErrorTranslator` → both Keycloak
   APIs. A record → one call. The depth of a package is not a filing habit, it
   says how far the knowledge inside it reaches.
2. **A comment is written only when the code lies or is silent without it.**
3. **Logic repeated twice moves into a util class** — not the first time,
   the second.

---

## Nothing left unwritten

Every class module 1 asks for exists. What is left is not classes: integration
tests on Testcontainers, a coverage number, a Postman collection, and
`person-client` published to Nexus rather than to a local Maven repository.
