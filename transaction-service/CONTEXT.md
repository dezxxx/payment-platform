# CONTEXT - transaction-service

Module 3. Shared rules, ports and how the platform is started: [`CONTEXT.md`](../CONTEXT.md).

---

## 1. Progress — module 3 (transaction-service)

Wallets and their transactions: deposit, transfer, withdrawal. Branch
`module-3-transaction-service`, PR #2. The teacher approved the design
(C4 diagrams in `transaction-service/diagrams-c4/`) on 2026-10-22; the code
follows the sequence diagrams step by step, and a question that comes up
while coding is decided before it is coded. The next push carries working code.

### Agreed deviations from the module 3 handout

| Handout | Here | Why |
|---|---|---|
| Wallets Service is a separate service with its own database (`WalletServiceClient`) | wallets live inside transaction-service, one database | the debit, the transaction row and the event commit in one database transaction |
| withdrawal: debit, then a separate `PENDING` write | one database transaction: lock the wallet, debit, `PENDING`, event | a crash between two steps would leave money debited with no transaction |
| confirm writes `PENDING`, then sends to Kafka | the event is written to the table `transaction_events` in the same transaction; `TransactionKafkaProducer` sends it to Kafka | the database and Kafka cannot be written atomically |
| `DepositCompleted` has no sender | Fake Payment Provider stands in for the external provider: reads `*Requested`, answers `*Completed` or `*Failed` | without it no answer comes and the operation never ends |
| no failure event for a deposit | `DepositFailed` → `FAILED`, the balance does not change | otherwise a failed deposit stays `PENDING` forever |
| provider answers carry no `userId` | they carry `userId` from the request | the shard is chosen by `user_uid`: one query to one shard |
| events in JSON, amounts as `100.0` | Avro + Schema Registry, amounts as `decimal` | the registry refuses an incompatible schema; floating point rounds money |
| `init` and `confirm` are not linked | `init` returns a `requestId` (stores nothing), the client sends it in `confirm`, which stores it in `transactions.request_id`, unique per `user_uid` | links the two requests in traces and logs; a repeated `confirm` returns the existing transaction instead of a second debit |
| `userUid` comes from the client (`/wallets/user/{userUid}`, `?userUid=`) | individuals-api fills it from the token; every other operation gets it in the header `X-User-Uid` | otherwise anyone could read others' wallets; the path already holds `walletUid` / `transactionId`, a GET has no body |
| one path `/transactions/{type}/init` (and `/confirm`) with a `oneOf` body | six paths `/transactions/{deposit,withdrawal,transfer}/{init,confirm}`, each with its own body | the same URLs for the client; different operations are not mixed, each body has its own class and its own validation |
| `/transactions/{transactionId}/confirm` in the list of functions | `/transactions/{type}/confirm`, as in the contract | `init` creates nothing, so there is no `transactionId` before `confirm` |
| Feign client in individuals-api | the generated reactive `transaction-service-client`, as `person-service-client` | individuals-api is WebFlux, Feign would block the event loop |
| ShardingSphere Spring Boot Starter | ShardingSphere-JDBC as the driver (`ShardingSphereDriver` + YAML rules) | the starter was removed in ShardingSphere 5.3 |
| `client/KafkaProducer`, `client/KafkaConsumer` | `client/TransactionKafkaProducer`, `client/TransactionKafkaConsumer` | Kafka's own classes share those names |
| port 8082 | 8084 | 8082 is person-service, 8083 is Nexus |
| transfer between any wallets | transfer between wallets of one user (one shard, one ACID transaction) | transfers between users are out of scope for now; they would touch two shards and become a saga over Kafka, which reuses `transaction_events` and the producer |

### Decisions

| Decision | Value |
|---|---|
| Web stack | Spring MVC, Spring Data JPA with Hibernate, over ShardingSphere-JDBC - blocking, like person-service. Only the generated `transaction-service-client` is reactive: it runs in individuals-api, never in transaction-service |
| Packages | by layer, as person-service (`entity`, `repository`, `service`, `mapper`, `rest`, `exception`, `config`, `validation`); inside `entity` grouped by topic: `entity.wallet` (`WalletEntity`, `WalletTypeEntity`, `WalletStatus`) and `entity.transaction` (`TransactionEntity`, `TransactionEventEntity`, `TransactionStatus`, `TransactionPaymentType`) - two topics, more entities to come. `TransactionPaymentType` is the Java name of the database enum `payment_type` |
| Sharding | 2 shards, key `user_uid`, `INLINE` algorithm `hash(user_uid) % 2` (`HASH_MOD` is an auto algorithm in 5.5.3 and would rename the tables to `wallets_0`, `wallets_1`); `wallets`, `transactions`, `transaction_events` by `user_uid`, `wallet_types` copied to every shard (`BROADCAST`). Rules in `sharding.yaml`. Every query carries `user_uid`, except the producer's scan of unsent events, which reads both shards on purpose |
| Migrations | Flyway runs on each shard directly (`ShardMigrations`), before the ShardingSphere DataSource is created: through ShardingSphere it would see one logical database. `flyway-core` without the Spring Boot starter. Each shard keeps its own `flyway_schema_history` |
| Schema | the handout's DDL, plus `transactions.request_id` and the table `transaction_events` (`payload JSONB`, `traceparent VARCHAR(55)`, `sent_at`) from the diagrams; tables in `public` |
| Delivery | at-least-once. `TransactionKafkaProducer` (`@Scheduled`) reads `transaction_events WHERE sent_at IS NULL`, sets `sent_at` only after the broker acknowledges, leaves it `NULL` on a failure so the next run retries. The word "outbox" is not used in names |
| Finalization | `TransactionKafkaConsumer` → `TransactionService.finalizeTransaction(transactionId, userId, outcome, reason)` (`finalize` is a method of `Object`) in one transaction: `UPDATE transactions … WHERE status = 'PENDING'`; 1 row - first processing (deposit credits, failed withdrawal returns `amount + fee`); 0 rows - a repeat (log INFO, nothing changes) or an unknown transaction (exception). An exception → rollback, retry with a pause (`DefaultErrorHandler`), then the DLT |
| Events | Avro, the second contract after OpenAPI: `avro/*.avsc` (`DepositRequested`, `DepositCompleted`, `DepositFailed`, the same three for withdrawal), namespace `com.dezxxx.transaction.event`. Classes generated by the official `avro-tools` (`-string -bigDecimal`): `uuid` → `UUID`, `decimal(19,2)` → `BigDecimal`. Schema Registry checks compatibility, the subject is `<topic>-value`. Avro 1.12 loads only trusted classes: `TrustedEvents.trust()` adds the event package |
| Events jar | `transaction-service-events` (its own source set: the generated classes, `EventTopics`, `TrustedEvents`) is published to Nexus next to the client; Fake Payment Provider imports it |
| Topics | kebab-case, one per event: `deposit-requested`, `deposit-completed`, `deposit-failed`, the same for withdrawal; 4 partitions; the key is `transactionId`. transaction-service creates them all (`KafkaAdmin.NewTopics`), the DLTs too: `<topic>-dlt` for the four answer topics |
| Trace over Kafka | `traceparent` travels as a Kafka header, never as an Avro field. The producer continues the trace stored with the event (`Propagator.extract`); the `KafkaTemplate` and listener observations write and read the header |
| Fake Payment Provider | its own module and container (`fake-payment-provider`, port 8086, package `com.dezxxx.provider`): `PaymentKafkaConsumer` → `PaymentService` → `PaymentKafkaProducer`. An amount up to `provider.approval-limit` (10000) → `*Completed`, above → `*Failed` with the reason. Read and answer in one Kafka transaction (`transaction-id-prefix`): exactly-once Kafka → Kafka; consumers on both sides `read_committed` |
| Metrics | `@Observed` on the services: `transaction_operations_seconds`, `wallet_operations_seconds` by `operation` and `error`. `TransactionMetrics`: `transaction_events_unsent` and `…_unsent_oldest_seconds`, `transaction_pending` and `…_pending_oldest_seconds` (read from both shards every `transaction.metrics.refresh-interval`, 15 s; a scrape never queries the database; the age is counted by the database, `now() - created_at`), `transaction_events_send_failures_total`, `transaction_events_dead_letter_total{topic}`. Kafka client metrics (consumer lag) come from Spring Boot. The provider counts `provider_payments_total{operation, outcome}` |
| Aggregates over the shards | native SQL with an alias (`count(*) AS pending`): ShardingSphere 5.5.3 merges an aggregate by its alias, and Hibernate writes JPQL aggregates without one (`Can't find index: AggregationProjection`) |
| Alerts | `infra/prometheus/alerts.yml`, no Alertmanager: `TransactionPendingTooLong` (oldest `PENDING` > 5 min), `TransactionEventsNotSent` (oldest unsent > 60 s), `TransactionDeadLetters` (any in 5 min), `TransactionConsumerLag` (> 100 for 5 min). Firing ones are on Prometheus `/alerts` and on the dashboard |
| Shard metrics | `postgres_exporter` per shard (job `transaction-shards`, label `shard`); the pools ShardingSphere opens (`ds_0`, `ds_1`) bound by `ShardPoolMetrics` with Spring Boot's `DataSourcePoolMetrics`: `jdbc_connections_*{name="ds_0"}`. ShardingSphere's default pool is 50 connections per shard. No full route counter: ShardingSphere-JDBC exposes none without its Java agent |
| Dashboard | `infra/grafana/dashboards/transaction-service.json`: HTTP, operations, the `transaction_events` backlog, `PENDING`, lag, DLT, the provider's answers, a Shards row (load, rows per table, size, connections, pools, locks, deadlocks) and the logs of both services |
| Idempotency of confirm | the first statement of the confirm transaction claims the `requestId`: `INSERT … 'PENDING', :requestId ON CONFLICT (user_uid, request_id) DO NOTHING RETURNING uid`. 1 row - this request owns it; 0 rows - a repeat: same parameters → `200` with the existing transaction and its status, other parameters → `409`. A concurrent second INSERT waits for the first transaction; a rollback frees the `requestId`. ShardingSphere 5.5.3 parses the statement; the concurrent case is checked by `ShardingIT` on two shards |
| Request validation | `@Valid` in the controller, before any database transaction: required fields, UUIDs, `amount > 0` with at most 2 decimals; for a transfer `walletUid ≠ targetWalletUid`. Failure → `400`, nothing written |
| Fee and balance | the fee is computed after the wallet is read and before the check `balance < amount + fee` → `400`, in init and again in confirm (the init terms are not trusted). `fee` is `NULL` on insert and set by `UPDATE` in the same transaction |
| Fee rate | a share of the amount per operation in `transaction.fee` (`FeeProperties`): deposit `0`, withdrawal `0.01`, transfer `0.005`; rounded half up to cents. A private method of `TransactionService`, its only user; an environment variable such as `TRANSACTION_FEE_WITHDRAWAL` overrides it |
| Unknown wallet in confirm | the claim INSERT runs first and `transactions.wallet_uid` references `wallets`: a wallet missing from the user's shard fails it on `transactions_wallet_uid_fkey`. `GlobalExceptionHandler` answers `404 WALLET_NOT_FOUND` for that constraint (as person-service does for the email index); the transaction rolls back, the `requestId` stays free |
| `payment_type` in SQL | the type is a literal in the `requestId` claim (`'DEPOSIT'`), one statement per operation: ShardingSphere 5.5.3 rewrites `CAST(:type AS payment_type)` into broken SQL. The search filter compares the column as text (`cast(type as varchar) = ?`): ShardingSphere binds the parameter as `varchar`, and PostgreSQL has no `payment_type = varchar` |
| Locks | confirm of a withdrawal and a transfer reads the wallet(s) `FOR NO KEY UPDATE`; a transfer locks both in `ORDER BY uid`, so two opposite transfers never deadlock. `NO KEY`, not `FOR UPDATE`: the claim INSERT has already put `FOR KEY SHARE` on the wallet through its foreign key, and `FOR UPDATE` would wait for that lock of a concurrent claim - two confirms on one wallet deadlocked. The lock guards different operations on one wallet, `requestId` guards repeats of the same one |
| Search | only the user's rows (`user_uid` first, so one shard); each filter given adds one condition (`TransactionSpecifications`); newest first, `uid` breaks a tie, so pages never overlap. A `Slice`, not a `Page`: the contract returns a plain array without a total, a `Page` would run a `count(*)` over all matching rows for nothing |
| Date range | `dateFrom` included, `dateTo` not; `dateFrom` after `dateTo` → `400 VALIDATION_ERROR` from `DateRangeValidator`, called by the service. Not a constraint on the controller: Bean Validation forbids parameter constraints on a method that implements an interface method, and the controller implements the generated `TransactionsApi` |
| Balance | never negative: `amount > 0`, the check after the fee, the lock. A `CHECK (balance >= 0)` in the migration as the last guard |
| Deposit | no balance check; the provider's refusal (not enough on the card, the bank refused) → `DepositFailed` |
| Soft delete | every wallet read carries `archived_at IS NULL` |
| Failures | a database error at any step → rollback, `500`, nothing written. A response lost after the commit → the client repeats confirm with the same `requestId` → `200` with the same transaction |
| Errors | RFC 9457 body with `timestamp`, `error`, `traceId`, `details`, as person-service |

### Contract

`transaction-service/openapi/transaction-service.yaml`, generated with the same
plugin and settings as person-service: server interfaces `WalletsApi`,
`TransactionsApi` (implemented by `WalletRestControllerV1`,
`TransactionRestControllerV1`) and the reactive `transaction-service-client`.
Avro classes of the events come from `.avsc` files in a later step.

| Item | Value |
|---|---|
| DTO names | schemas exactly the handout's: `CreateWalletRequest`, `WalletResponse`, `DepositInitRequest`, `WithdrawalInitRequest`, `TransferInitRequest`, `TransactionInitResponse`, `DepositConfirmRequest`, `WithdrawalConfirmRequest`, `TransferConfirmRequest`, `TransactionConfirmResponse`, `TransactionStatusResponse`, `ErrorResponse`. The generated Java classes carry a `Dto` suffix (`modelNameSuffix`), as in person-service: `WalletResponseDto` goes over HTTP, the entity stays inside |
| `TransactionInitResponse` | `amount`, `fee`, `total`, `requestId` |
| `TransactionConfirmResponse` | `transactionId`, `status` |
| `TransactionStatusResponse` | the same in the status and in the search: `transactionId`, `type`, `status`, `walletUid`, `targetWalletUid`, `amount`, `fee`, `failureReason`, `createdAt` |
| `amount` checks | `> 0` from `exclusiveMinimum`; at most 2 decimals - the standard `@Digits(fraction = 2)` put on the field through `x-field-extra-annotation` |
| `walletUid ≠ targetWalletUid` | our own class-level `@DifferentWallets` with a `ConstraintValidator` in the `validation` package, put on `TransferInitRequest` and `TransferConfirmRequest` through `x-class-extra-annotation`. Without it the same wallet twice reads as one row and would answer `404` |
| `TransferWallets` | interface with `getWalletUid()` and `getTargetWalletUid()`; both transfer DTOs implement it through `x-implements`, so the validator reads them without knowing the generated classes and works in the server and in the client jar alike |
| Enum query parameters | `?type=deposit` is converted by the contract value through the generated `EnumConverterConfiguration` (`fromValue`); `Enum.valueOf` knows only `DEPOSIT`. An unknown value → `400 VALIDATION_ERROR` |
| Client | `openApiGenerateClient`: `spring-http-interface`, reactive; its own `client` source set and `transaction-service-client` jar holding the client API, its DTOs and `@DifferentWallets` with its validator and `TransferWallets` (only these files of `validation`: the rest needs the service's own classes) |

### Done

- [x] Module skeleton: `com.dezxxx.transaction`, Spring MVC, port 8084, unit and
      integration test tasks, `gradlew` executable
- [x] C4 diagrams: container, component and eight sequence diagrams (deposit,
      transfer, withdrawal, wallet create, wallet by id, a user's wallets,
      transaction status, search). Every failure branch, transaction boundaries
      and what is written to the database. English in the repository
- [x] The design review: every comment of the teacher answered in the diagrams;
      the design is approved
- [x] Contract decisions above
- [x] The contract `openapi/transaction-service.yaml`, server and client generation,
      `@DifferentWallets` with its unit tests. Swagger UI at `/swagger-ui.html` shows the contract file
      itself (springdoc, as in person-service); the endpoints not written yet answer `501`
- [x] `transaction-service-client` is published (`maven-publish`, as in person-service);
      in the `Makefile` under `transaction-*`
- [x] Migrations V001-V002, ShardingSphere-JDBC over two PostgreSQL shards, Flyway
      on each shard; `ShardingIT` checks both shards, the broadcast table, one shard
      per wallet, the concurrent `requestId` claim and `CHECK (balance >= 0)`
- [x] `Dockerfile`, both shards in compose, `make verify` / `rebuild` build the image,
      Prometheus scrapes `/actuator/prometheus`

- [x] Wallets by the diagrams: create, by id, a user's wallets. `WalletRestControllerV1` →
      `WalletService` → `WalletRepository`, `WalletTypeRepository`; `WalletMapper`;
      `GlobalExceptionHandler` (RFC 9457, as person-service) with `TransactionServiceException`
      (Spring already has a `TransactionException`). Wallet types USD and EUR seeded by V003,
      the uid ends with the ISO 4217 number (`…0840`, `…0978`). Tracing over OTLP, as person-service
- [x] Deposit init and confirm by the diagram: `TransactionRestControllerV1` → `TransactionService` →
      `TransactionRepository`, `TransactionEventRepository`, `WalletService`; `TransactionMapper`.
      confirm claims the `requestId`, writes the `PENDING` transaction and an unsent `DepositRequested`
      (JSON payload, `traceparent`) in one database transaction; a repeat → `200`, other terms → `409`
- [x] Kafka (KRaft) + Schema Registry in compose; Avro events and the `transaction-service-events` jar;
      `TransactionKafkaProducer` (sends `transaction_events`, continues the trace), `TransactionKafkaConsumer`
      (finalization, retries, DLT); `TransactionKafkaIT` against a real Kafka
- [x] Fake Payment Provider: its own module, image and `provider-*` targets in the `Makefile`
- [x] Metrics, alerts and the Grafana dashboard above; `TransactionMetricsIT` checks the sum over both
      shards and the shard pools; `postgres_exporter` per shard
- [x] Withdrawal init and confirm by the diagram: init and confirm check `balance >= amount + fee`
      (`400 INSUFFICIENT_FUNDS`); confirm reads the wallet `FOR NO KEY UPDATE`, debits `amount + fee` (a reserve),
      writes the `PENDING` transaction and `WithdrawalRequested` in one database transaction.
      `WithdrawalFailed` returns the reserve, `WithdrawalCompleted` keeps it. `TransactionWithdrawalIT`
      races two confirms on one wallet: one `200`, one `400`, the balance never below zero
- [x] Transfer init and confirm by the diagram: both wallets are the user's, `ACTIVE` and of one currency
      (`400 CURRENCY_MISMATCH`); confirm locks both `ORDER BY uid FOR NO KEY UPDATE`, debits `amount + fee`,
      credits `amount` and finishes the transaction `COMPLETED` in one database transaction, without Kafka.
      `TransactionTransferIT` races A→B against B→A: both `200`, no deadlock
- [x] Transaction status and search by the diagrams: the status of the user's own transaction (another
      user's → `404 TRANSACTION_NOT_FOUND`); the search by wallet, type, status and dates, page by page,
      newest first. `TransactionStatusSearchIT` checks every filter, pages without overlap, another
      user's wallet → an empty list and the `400` answers

### Next

- [ ] Postman collection; `transaction-service-client` in individuals-api
- [ ] Query time per shard: `pg_stat_statements` is not loaded in the shards, the dashboard shows
      the service-side time; a full route counter needs the ShardingSphere Java agent
- [ ] Add transaction-service to the configuration map of the root `CONTEXT.md`
