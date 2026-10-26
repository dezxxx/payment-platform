# CONTEXT - person-service

Module 2. Shared rules, ports and how the platform is started: [`CONTEXT.md`](../CONTEXT.md).

---

## 1. Naming: `person-service` or `users-service`

The module 1 handout names the domain service `person-service`, and this
repository follows it: the Gradle module, the contract, the generated
`person-client` artifact, the `person` database schema and the
`individuals.person-service.*` properties all use that name. The platform
description in §1 of `../CONTEXT.md` calls the same service **Users Service**.

Treated as one service under two names until the course says otherwise. If a
later module requires the second name, the rename touches: `settings.gradle.kts`,
the module directory, `person-service/openapi/person-service.yaml`, the
published artifact coordinates, `PersonsApi`, the Flyway schema, the compose
service `person-postgres`, and `PersonServiceProperties`. It is a mechanical
change, but it is not a small one, so it is not done speculatively.

---

## 2. Progress — module 2 (person-service)

The module 2 practice handout is the script, followed step by step in the order
of its "implementation steps".

### Agreed deviations from the module 2 handout

| Handout | Here | Agreed |
|---|---|---|
| `/api/v1/users`, the user is the root and holds the individual | `/api/v1/individuals`, the **individual** is the root and holds its user, the user holds the address; DTOs `CreateIndividualRequest`, `UpdateIndividualRequest`, `IndividualResponse` | with the teacher at the review, 2026-10-11: the individual is the root (a user is the account an individual - and later a legal entity - holds), the API and DTOs are built around it |
| `DELETE /api/v1/users/{id}` deletes the whole aggregate | `DELETE /api/v1/individuals/{id}` is a soft delete: the individual becomes `ARCHIVED` with `archived_at`, its rows stay, reads no longer find it, the email stays taken | with the teacher, 2026-10-11 |
| the registration is compensated through that `DELETE` | a separate `DELETE /api/v1/individuals/compensate-registration/{id}`: a hard delete of individual, user and address, called only by individuals-api | the teacher, 2026-10-11: "Нужен и ты должен его добавить", "Через DELETE не стоит - нужна логика именно компенсации" |
| DDL: `passport_number`, `phone_number`, the links nullable; `users.secret_key` | NOT NULL on every part of the aggregate, `secret_key` dropped, `status` limited to `NEW`, `VERIFIED`, `ARCHIVED` | ours, under the handout's own "fix the gaps you find": an aggregate is born whole |

The handout's example requests, as this API takes them:

| Handout | Here |
|---|---|
| `POST /api/v1/users` `{email, firstName, lastName, address, individual: {passportNumber, phoneNumber}}` | `POST /api/v1/individuals` `{passportNumber, phoneNumber, user: {email, firstName, lastName, address}}` |
| `GET /api/v1/users/by-email?email=…` | `GET /api/v1/individuals/by-email?email=…` |
| `PATCH /api/v1/users/{id}` `{firstName, lastName, address: {city}}` | `PATCH /api/v1/individuals/{id}` `{user: {firstName, lastName, address: {city}}}` |
| `DELETE /api/v1/users/{id}` | `DELETE /api/v1/individuals/{id}` (archive) or `DELETE /api/v1/individuals/compensate-registration/{id}` (remove) |

After a soft delete the email stays taken: registering it again answers `409`.
The API never frees it - that is a deliberate support action.

### Acceptance criteria - module 2, as agreed

| # | Scenario | Expected |
|---|---|---|
| 1 | create with every required field | `201`; individual, user and address in one transaction; status `NEW` |
| 2 | create with an email already taken, in any letter case | `409 EMAIL_ALREADY_EXISTS`, nothing stored |
| 3 | create with a missing or empty field (`email: ""` included) or an unknown country | `400`, each field named in `details`, nothing stored |
| 4 | read by id, by email in another letter case | `200`, the whole aggregate |
| 5 | read an unknown id or an archived individual | `404 INDIVIDUAL_NOT_FOUND` |
| 6 | PATCH with some fields of the individual, its user and address | `200`; only the fields sent change, in one transaction and one audit revision |
| 7 | two PATCHes of one individual at the same time | one `200`, the other `409 CONCURRENT_MODIFICATION` |
| 8 | `DELETE /{id}` | `204`; rows stay with `ARCHIVED` and `archived_at`; then GET, by-email, PATCH and DELETE answer `404` |
| 9 | register the same email again after `DELETE /{id}` | `409` - the email stays taken |
| 10 | `DELETE /compensate-registration/{id}` | `204`; no row of individual, user or address is left; the same email registers again with `201` |
| 11 | registration through individuals-api when a Keycloak step fails | the individual is removed by compensation, the client gets the original error, the email is free |
| 12 | `/me` after the last name changed in person-service | the new last name - the profile is read from person-service |
| 13 | create, change, archive, compensate | each leaves an Envers revision, with `*_mod` flags on the changed fields |
| 14 | any error | RFC 9457 body (`application/problem+json`) with `timestamp`, `error`, `traceId`, `details`; the headers Spring requires are kept (`Allow` on `405`) |

### Decisions

| Decision | Value |
|---|---|
| Web stack | Spring MVC over JPA - blocking, unlike individuals-api |
| DTO ↔ entity mapping | written by hand, no MapStruct |
| Aggregate root | **the individual**. A user is the account (email, name, address) that an individual - and later a legal entity - holds; never the other way round. `IndividualEntity` owns `user` (`individuals.user_id`, cascade ALL), `UserEntity` knows nothing of the individual. The handout's `/api/v1/users` with the user on top was reworked after the review |
| DTO names | the generated Java classes carry a `Dto` suffix (`modelNameSuffix`): `IndividualResponseDto` next to `IndividualEntity`, so the code tells the two apart at a glance. Schemas: `CreateIndividualRequest`, `UpdateIndividualRequest`, `IndividualResponse`, `ProblemResponse`; user and address go inline inside them |
| API | `/api/v1/individuals`: POST → 201, GET `/{id}`, GET `/by-email?email=`, PATCH `/{id}`, DELETE `/{id}` → 204 (soft), DELETE `/compensate-registration/{id}` → 204 (hard), served by `IndividualRestControllerV1`. Body: the individual on top (`passportNumber`, `phoneNumber`, `status`…), its `user` inside, the `address` inside the user |
| Create | `passportNumber`, `phoneNumber`, `user`, its `address` and `countryAlpha3` are **required**: the aggregate is born whole (individual, user, address in one transaction) and PATCH only ever changes what exists. Unknown country code → 400 |
| Identifier | `individuals.id` (UUID) is the domain identifier: the API returns it and takes it in `/{id}`; individuals-api stores it in Keycloak as `user_uid`. person-service knows nothing about Keycloak |
| Email | unique regardless of case (`lower(email)` index); not changed by PATCH |
| Errors | RFC 9457 (`type`, `title`, `status`, `detail`, `instance`, `application/problem+json`) plus the course-wide `timestamp`, `error`, `traceId`, `details` as extension members - one body satisfies both handouts. `type` = `https://example.org/problems/<code-in-kebab-case>` as in the handout's example, `title` = the HTTP reason phrase, `detail` = the `ErrorCode` text. One `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`, so Spring's own failures (bad JSON, 405, 415, `@Valid`) get the same body. Error texts are always English (`spring.web.locale: en`, fixed resolver) |
| Migrations | one per step: schema → tables → indexes and constraints → countries; Envers audit tables as V005 |
| Delete | two operations. `DELETE /{id}` is a soft delete: the individual becomes `ARCHIVED` with `archived_at`, user and address stay as they are, and every read (`/{id}`, `/by-email`, PATCH, DELETE) finds active individuals only. `DELETE /compensate-registration/{id}` is a hard delete of the whole aggregate, called only by individuals-api when the Keycloak part of a registration fails: data that never became a registration is not kept. An archived individual keeps its email taken |
| Schema | the handout's DDL tightened to the aggregate: `individuals.user_id`, `passport_number`, `phone_number`, `users.address_id`, `addresses.country_id` are NOT NULL; individuals carry `created`/`updated` like the user and the address; `users.secret_key` is dropped (never used, and person-service holds no secrets of other systems). `status` is `NEW`, `VERIFIED` or `ARCHIVED` (enum `IndividualStatus`, a CHECK constraint, an enum in the contract); create sets `NEW`, the soft delete sets `ARCHIVED`; `VERIFIED` is the base for verification later |
| Indexes | where a query or a foreign key needs one, and never twice: a UNIQUE constraint already is an index |
| Audit | Hibernate Envers writes the history into its own schema `person_history` (`users_history`, `addresses_history`, `individuals_history`, `revinfo`), Spring Data Envers reads it (`RevisionRepository` on User and Individual). Modified flags on all three audited entities - the handout asks for User and Individual at least, and PATCH changes the address most. Countries are not audited: a migration fills them and the API never changes them. A deleted user keeps its data in the last revision (`store_data_at_delete`) |
| Entities | `IndividualEntity` is the aggregate root: it owns the user, the user owns the address (`@OneToOne`, cascade ALL), so the whole aggregate is saved and deleted through the individual; all associations LAZY |
| Time | entities hold `Instant` - a point in time, the same wherever the server runs; mapped to the DDL's plain `TIMESTAMP` columns (`preferred_instant_jdbc_type`) and stored as UTC (`hibernate.jdbc.time_zone`); the JSON carries a `Z`. `LocalDateTime` was dropped: `@CreationTimestamp` filled it with the server's local clock |
| Local port | 8082 in compose; 8092 when started from the IDE (`SERVER_PORT=8092 ./gradlew bootRun`), since compose holds 8082 |
| Flyway history | `person.flyway_schema_history`, next to the tables (`spring.flyway.default-schema: person`). Left to `search_path`, it moved: the database user is also called `person`, so `"$user"` pointed to `public` before V001 and to `person` after it, and the second start failed |
| Repositories | four, one per entity, as in the teacher's project. Email is looked up with `lower(email) = lower(:email)`, not the derived `…IgnoreCase` (that one is `upper()` and cannot use `uk_users_email_lower`) |
| Exceptions | one `PersonException` carrying an `ErrorCode` (status + message), the same pattern as module 1's `ApiException`. `VALIDATION_ERROR` / `MALFORMED_REQUEST` / `COUNTRY_NOT_FOUND` 400, `INDIVIDUAL_NOT_FOUND` 404, `EMAIL_ALREADY_EXISTS` / `CONCURRENT_MODIFICATION` 409, framework `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR` 500. A second create racing past the email check is stopped by `uk_users_email_lower` and still answers 409 |
| Transactions | `IndividualService` is `@Transactional(readOnly = true)`; create, update and delete open a writing transaction. The response is built inside the transaction, so LAZY associations load there and nothing lazy leaves the service |
| Client | `person-service-client` replaces `person-client`; individuals-api moves to it and gains the registration compensation - `DELETE /compensate-registration/{id}` when the Keycloak part fails |
| Contract evolution | module 2 publishes no Kafka events yet, but later modules will carry the same data as Avro events with schemas in Confluent Schema Registry, which refuses an incompatible schema. The OpenAPI contract follows the same rules now: fields are only added, and only as optional ones with a default; a published field is never renamed, removed or retyped; a breaking change is a new version (`/api/v2/...`), not an edit of v1. Events will reuse the REST names and formats: the individual's id as `user_uid`, times in UTC ISO-8601, `status` as a string (`NEW`, `VERIFIED`, `ARCHIVED`) |

### Done

- [x] Build: Spring MVC, JPA, Flyway, Envers, actuator, OTLP; plugin versions in
      its own `build.gradle.kts`, no shared catalog
- [x] Contract rewritten per the handout and validated
- [x] Server interfaces generated into `build/generated/openapi`
      (`interfaceOnly` + `delegatePattern` + `useSpringBoot4`, JSpecify `@Nullable`)
- [x] C4 diagrams inside the module, `person-service/diagrams-c4/`: container, component and
      sequence (create, archive, compensate), EN + RU, a PNG next to each
- [x] `person-service-client` generated by its own task (`spring-http-interface`,
      reactive for WebFlux individuals-api) into its own source set and jar;
      `person-service.jar` carries none of it
- [x] Migrations V001-V004 from the handout's DDL, with these changes:
      `countries.name` is `VARCHAR(64)`, not 32 (seven ISO names are longer);
      unique and foreign-key constraints live in V003 next to the indexes, not
      in the tables; `addresses.country_id` got its index; `users.address_id`
      is UNIQUE - two users may live at the same address, but each has an
      address row of their own, so moving one never moves the other; the
      handout's `idx_individuals_user_id` and `idx_users_address_id` were
      dropped as duplicates of those UNIQUE constraints
- [x] JPA entities `UserEntity`, `AddressEntity`, `IndividualEntity`,
      `CountryEntity`, and a minimal `application.yml` (`ddl-auto: validate`,
      UTC, `open-in-view: false`). Checked against the compose database:
      Flyway applies V001-V004 and Hibernate accepts every entity
- [x] Three mappers by hand, one per entity as in the teacher's project:
      `AddressMapper`, `IndividualMapper`, and `UserMapper` for the aggregate,
      which delegates to the other two. Entity ↔ generated DTO and nothing else -
      no database calls, no business decisions. The country is looked up by the
      service and handed in; on PATCH a null field means "leave as is". The UTC
      conversion both mappers need lives in `util/DateTimeUtil`
- [x] `IndividualService` with the six operations, four repositories, `PersonException`
      + `ErrorCode`. Create checks the email, finds the country (alpha3 first,
      then alpha2), sets status `NEW` and `filled`; update flushes so the response
      carries the new version and time. Starts against the compose database:
      Spring Data accepts every repository query
- [x] Envers: V005 creates `person_history` with three history tables and
      `revinfo` (own `RevisionInfoEntity`); `@Audited(withModifiedFlag = true)` on
      User, Address, Individual; `*_mod` flags named after the columns. The schema
      check at start-up passes against the compose database; through the API a
      create, a PATCH and a delete leave revisions 0, 1 and 2
- [x] `IndividualRestControllerV1` implements the generated `IndividualsApi`: five one-line methods,
      201 / 200 / 204, no logic. Swagger UI at `/swagger-ui.html` shows the contract
      file itself (springdoc, the same way as individuals-api)
- [x] Error layer (step 7): `ProblemResponse` in the contract carries the course
      fields, `GlobalExceptionHandler` answers every failure as RFC 9457. Checked live:
      400 / 404 / 405 / 409 / 415, and 12 parallel PATCHes give 409
      `CONCURRENT_MODIFICATION` to the ones that lost the optimistic lock
- [x] Postman collection `postman/person-service.postman_collection.json`: 22
      requests, 106 assertions - the five operations, every failure with its RFC 9457
      body, the delete, and what they left in `/actuator/prometheus`; runs again on
      the same database
- [x] Observability, the service side: `health`, `info`, `prometheus`, `metrics`
      exposed; ECS JSON logs carrying `traceId`, `spanId`, `service.name` and
      `user_uid`; traces over OTLP to Tempo, every `IndividualService` method a span
      (`individualService.create`...) under the HTTP span and a timer
      `individual_operations_seconds{operation}`. Checked with a local Tempo: a log line's
      `spanId` is the `individualService.create` span of the same trace. `user_uid` is also an
      attribute of the `individualService.*` span, so Tempo finds an individual's requests
      (`{span.user_uid="..."}`); never a metric label - one series per user would
      sink Prometheus. Named `user_uid`, not OpenTelemetry's `user.id`, so the same
      name finds the user in individuals-api, the Keycloak token and person-service

- [x] Docker image and compose: `person-service/Dockerfile` builds the jar inside the
      image (context: the `person-service` folder, own Gradle cache id so it can build
      next to individuals-api); compose runs the real service on 8082, started only
      once `person-postgres` is healthy, with a healthcheck on `/actuator/health`
      (bash `/dev/tcp` - the JRE image has no curl). Prometheus scrapes
      `person-service:8082`, Alloy keeps `user_uid` as structured metadata, Grafana
      has a `person-service` dashboard. Checked on a clean stack: both Postman
      collections green, a Loki line opens its Tempo trace
- [x] Tests of person-service, given / when / then: 39 unit (IndividualService, the
      mappers, GlobalExceptionHandler, DateTimeUtil, UserUidLogFilter) and 19
      integration on a real PostgreSQL (Testcontainers): every scenario the handout
      lists - create, a repeated email -> 409, read by id and by email, one PATCH of
      individual, user and address, rollback of a partial change, delete leaving no row, Envers
      revisions and modified flags, health and metrics. Coverage 97% of lines, the
      build fails below 80% (`./gradlew check`)
- [x] `person-service-client` published to Nexus by person-service itself
      (`make publish`): the client jar and a pom listing its dependencies
- [x] individuals-api on the new client, from Nexus by coordinates:
      - registration takes address and individual and passes them to
        `POST /api/v1/individuals`; an unknown country code answers 400
        `VALIDATION_ERROR` naming the address
      - compensation: when the Keycloak part fails, the individual is deleted
        (`DELETE /api/v1/individuals/compensate-registration/{id}`) and the caller gets the original error;
        `REGISTRATION_INCONSISTENT` only when that delete fails too. Checked live:
        Keycloak stopped -> 503, no row left, Envers history shows the create and
        the delete
      - `KeycloakTokenResponseDto`, `KeycloakUserRequestDto`
      - waits for a healthy person-service and a healthy Keycloak, and has a
        healthcheck of its own;
        the WireMock stub and the old `person-client` module are gone
- [x] Both services answer in English whatever the client's `Accept-Language` or
      the server locale (`spring.web.locale: en`, fixed resolver): an API returns
      stable texts and codes, the user interface translates
- [x] Traces go through Alloy, the agent: both services push OTLP to
      `alloy:4318`, Alloy batches them and passes them on to Tempo - as the handout
      prefers over a direct export. One registration is one trace across both
      services (`registration` -> `http post /api/v1/individuals` -> `individualService.create`)
- [x] Checked on a clean stack: both Postman collections green (individuals-api
      20 requests / 52 assertions, person-service 22 / 106), both builds green with
      every test suite

### Next

- [ ] Your full manual test of the application
- [ ] Decide the commits and the branches they go to, then push and open the PR
      `module-2-person-service` -> `main`
