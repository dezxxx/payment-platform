# CONTEXT - transaction-service (русская версия)

Модуль 3. Общие правила, порты и как запускается платформа: [`CONTEXT.ru.md`](../CONTEXT.ru.md).

---

## 1. Прогресс — модуль 3 (transaction-service)

Кошельки и их транзакции: пополнение, перевод, вывод. Ветка
`module-3-transaction-service`, PR #2. Учитель утвердил дизайн
(C4-диаграммы в `transaction-service/diagrams-c4/`) 2026-10-22; код идёт
по sequence-диаграммам шаг за шагом, а вопрос, возникший по ходу, решается
до того, как его код написан. Следующий push несёт рабочий код.

### Согласованные отклонения от ТЗ модуля 3

| ТЗ | Здесь | Почему |
|---|---|---|
| Wallets Service — отдельный сервис со своей БД (`WalletServiceClient`) | кошельки внутри transaction-service, одна БД | списание, строка транзакции и событие фиксируются одной транзакцией БД |
| вывод: списание, потом отдельно запись `PENDING` | одна транзакция БД: блокировка кошелька, списание, `PENDING`, событие | сбой между двумя шагами оставил бы деньги списанными без транзакции |
| confirm пишет `PENDING`, потом отправляет в Kafka | событие пишется в таблицу `transaction_events` той же транзакцией; `TransactionKafkaProducer` отправляет его в Kafka | БД и Kafka нельзя записать атомарно |
| у `DepositCompleted` нет отправителя | Fake Payment Provider — имитация внешнего провайдера: читает `*Requested`, отвечает `*Completed` или `*Failed` | без него ответ не придёт и операция не завершится |
| у пополнения нет события неудачи | `DepositFailed` → `FAILED`, баланс не меняется | иначе неуспешное пополнение навсегда `PENDING` |
| ответы провайдера без `userId` | несут `userId` из запроса | шард выбирается по `user_uid`: один запрос в один шард |
| события в JSON, суммы как `100.0` | Avro + Schema Registry, суммы как `decimal` | registry отклоняет несовместимую схему; плавающая точка округляет деньги |
| `init` и `confirm` ничем не связаны | `init` возвращает `requestId` (ничего не сохраняя), клиент передаёт его в `confirm`, тот сохраняет его в `transactions.request_id`, уникально в пределах `user_uid` | связь двух запросов в трассах и логах; повторный `confirm` возвращает уже созданную транзакцию, а не списывает второй раз |
| `userUid` приходит от клиента (`/wallets/user/{userUid}`, `?userUid=`) | individuals-api подставляет его из токена; остальные операции получают его в заголовке `X-User-Uid` | иначе можно читать чужие кошельки; путь уже занят `walletUid` / `transactionId`, у GET нет тела |
| один путь `/transactions/{type}/init` (и `/confirm`) с телом `oneOf` | шесть путей `/transactions/{deposit,withdrawal,transfer}/{init,confirm}`, у каждого своё тело | адреса для клиента те же; разные операции не смешиваются, у каждого тела свой класс и своя проверка |
| `/transactions/{transactionId}/confirm` в списке функций | `/transactions/{type}/confirm`, как в контракте | `init` ничего не создаёт, до `confirm` нет `transactionId` |
| Feign-клиент в individuals-api | сгенерированный реактивный `transaction-service-client`, как `person-service-client` | individuals-api на WebFlux, Feign заблокировал бы event loop |
| ShardingSphere Spring Boot Starter | ShardingSphere-JDBC как драйвер (`ShardingSphereDriver` + YAML с правилами) | starter удалён в ShardingSphere 5.3 |
| `client/KafkaProducer`, `client/KafkaConsumer` | `client/TransactionKafkaProducer`, `client/TransactionKafkaConsumer` | такие имена уже у классов самой Kafka |
| порт 8082 | 8084 | 8082 — person-service, 8083 — Nexus |
| перевод между любыми кошельками | перевод между кошельками одного пользователя (один шард, одна ACID-транзакция) | переводы между пользователями сейчас не делаем; они затронут два шарда и станут сагой через Kafka на тех же `transaction_events` и producer |

### Решения

| Решение | Значение |
|---|---|
| Веб-стек | Spring MVC, Spring Data JPA с Hibernate поверх ShardingSphere-JDBC — блокирующий, как person-service. Реактивный только сгенерированный `transaction-service-client`: он работает в individuals-api, а не в transaction-service |
| Пакеты | по слоям, как в person-service (`entity`, `repository`, `service`, `mapper`, `rest`, `exception`, `config`, `validation`); внутри `entity` по темам: `entity.wallet` (`WalletEntity`, `WalletTypeEntity`, `WalletStatus`) и `entity.transaction` (`TransactionEntity`, `TransactionEventEntity`, `TransactionStatus`, `TransactionPaymentType`) — две темы, сущностей станет больше. `TransactionPaymentType` — Java-имя enum `payment_type` из базы данных |
| Шардирование | 2 шарда, ключ `user_uid`, алгоритм `INLINE` `hash(user_uid) % 2` (`HASH_MOD` в 5.5.3 — автоматический и переименовал бы таблицы в `wallets_0`, `wallets_1`); `wallets`, `transactions`, `transaction_events` по `user_uid`, `wallet_types` — копия на каждом шарде (`BROADCAST`). Правила в `sharding.yaml`. Каждый запрос идёт с `user_uid`, кроме выборки неотправленных событий producer-ом, которая намеренно читает оба шарда |
| Миграции | Flyway работает с каждым шардом напрямую (`ShardMigrations`), до создания источника данных ShardingSphere: через ShardingSphere он видел бы одну логическую базу. `flyway-core` без стартера Spring Boot. У каждого шарда своя `flyway_schema_history` |
| Схема | DDL из ТЗ, плюс `transactions.request_id` и таблица `transaction_events` (`payload JSONB`, `traceparent VARCHAR(55)`, `sent_at`) из диаграмм; таблицы в `public` |
| Доставка | at-least-once. `TransactionKafkaProducer` (`@Scheduled`) читает `transaction_events WHERE sent_at IS NULL`, ставит `sent_at` только после подтверждения broker-а, при ошибке оставляет `NULL`, и следующий запуск повторяет. Слово «outbox» в именах не используется |
| Финализация | `TransactionKafkaConsumer` → `TransactionService.finalizeTransaction(transactionId, userId, outcome, reason)` (`finalize` — метод `Object`) одной транзакцией: `UPDATE transactions … WHERE status = 'PENDING'`; 1 строка — первая обработка (пополнение зачисляет, неуспешный вывод возвращает `amount + fee`); 0 строк — повтор (log INFO, ничего не меняется) или неизвестная транзакция (исключение). Исключение → откат, повтор с паузой (`DefaultErrorHandler`), затем DLT |
| События | Avro, второй контракт после OpenAPI: `avro/*.avsc` (`DepositRequested`, `DepositCompleted`, `DepositFailed` и те же три для вывода), namespace `com.dezxxx.transaction.event`. Классы генерирует официальный `avro-tools` (`-string -bigDecimal`): `uuid` → `UUID`, `decimal(19,2)` → `BigDecimal`. Schema Registry проверяет совместимость, subject — `<topic>-value`. Avro 1.12 загружает только доверенные классы: `TrustedEvents.trust()` добавляет пакет событий |
| Jar событий | `transaction-service-events` (свой source set: сгенерированные классы, `EventTopics`, `TrustedEvents`) публикуется в Nexus рядом с клиентом; Fake Payment Provider импортирует его |
| Топики | kebab-case, по одному на событие: `deposit-requested`, `deposit-completed`, `deposit-failed`, то же для вывода; 4 партиции; ключ — `transactionId`. Создаёт их все transaction-service (`KafkaAdmin.NewTopics`), DLT тоже: `<topic>-dlt` для четырёх топиков ответов |
| Трейс через Kafka | `traceparent` идёт заголовком Kafka, никогда не полем Avro. Producer продолжает трейс, сохранённый с событием (`Propagator.extract`); observation у `KafkaTemplate` и у listener-а пишут и читают заголовок |
| Fake Payment Provider | свой модуль и контейнер (`fake-payment-provider`, порт 8086, пакет `com.dezxxx.provider`): `PaymentKafkaConsumer` → `PaymentService` → `PaymentKafkaProducer`. Сумма до `provider.approval-limit` (10000) → `*Completed`, больше → `*Failed` с причиной. Чтение и ответ в одной транзакции Kafka (`transaction-id-prefix`): exactly-once Kafka → Kafka; консьюмеры с обеих сторон `read_committed` |
| Метрики | `@Observed` на сервисах: `transaction_operations_seconds`, `wallet_operations_seconds` по `operation` и `error`. `TransactionMetrics`: `transaction_events_unsent` и `…_unsent_oldest_seconds`, `transaction_pending` и `…_pending_oldest_seconds` (читаются с обоих шардов раз в `transaction.metrics.refresh-interval`, 15 с; scrape в базу не ходит; возраст считает база, `now() - created_at`), `transaction_events_send_failures_total`, `transaction_events_dead_letter_total{topic}`. Метрики Kafka-клиента (lag консьюмера) даёт Spring Boot. Провайдер считает `provider_payments_total{operation, outcome}` |
| Агрегаты по шардам | native SQL с алиасом (`count(*) AS pending`): ShardingSphere 5.5.3 сливает агрегат по алиасу, а Hibernate пишет агрегаты JPQL без него (`Can't find index: AggregationProjection`) |
| Алерты | `infra/prometheus/alerts.yml`, без Alertmanager: `TransactionPendingTooLong` (самая старая `PENDING` > 5 мин), `TransactionEventsNotSent` (самое старое неотправленное > 60 с), `TransactionDeadLetters` (хоть одно за 5 мин), `TransactionConsumerLag` (> 100 в течение 5 мин). Сработавшие видны на `/alerts` Prometheus и на дашборде |
| Метрики шардов | `postgres_exporter` на каждый шард (job `transaction-shards`, метка `shard`); пулы, которые открывает ShardingSphere (`ds_0`, `ds_1`), подключает `ShardPoolMetrics` штатным `DataSourcePoolMetrics` из Spring Boot: `jdbc_connections_*{name="ds_0"}`. Пул ShardingSphere по умолчанию — 50 соединений на шард. Счётчика full route нет: ShardingSphere-JDBC без своего Java agent его не отдаёт |
| Дашборд | `infra/grafana/dashboards/transaction-service.json`: HTTP, операции, очередь `transaction_events`, `PENDING`, lag, DLT, ответы провайдера, ряд Shards (нагрузка, строки по таблицам, размер, соединения, пулы, блокировки, deadlock-и) и логи обоих сервисов |
| Идемпотентность confirm | первая команда транзакции confirm занимает `requestId`: `INSERT … 'PENDING', :requestId ON CONFLICT (user_uid, request_id) DO NOTHING RETURNING uid`. 1 строка — запрос его занял; 0 строк — повтор: те же параметры → `200` с уже созданной транзакцией и её статусом, другие → `409`. Одновременный второй INSERT ждёт окончания первой транзакции; откат освобождает `requestId`. ShardingSphere 5.5.3 разбирает эту команду; одновременный случай проверяет `ShardingIT` на двух шардах |
| Проверка запроса | `@Valid` в контроллере до любой транзакции БД: обязательные поля, UUID, `amount > 0` и не больше 2 знаков после запятой; у перевода `walletUid ≠ targetWalletUid`. Ошибка → `400`, ничего не записано |
| Комиссия и баланс | fee считается после чтения кошелька и до проверки `balance < amount + fee` → `400`, в init и заново в confirm (условиям из init не доверяем). `fee` при вставке `NULL`, заполняется `UPDATE` в той же транзакции |
| Размер комиссии | доля суммы на операцию в `transaction.fee` (`FeeProperties`): deposit `0`, withdrawal `0.01`, transfer `0.005`; округление до копеек, от пяти вверх. Private-метод `TransactionService`, единственного, кто её считает; переменная окружения, например `TRANSACTION_FEE_WITHDRAWAL`, её переопределяет |
| Неизвестный кошелёк в confirm | INSERT захвата идёт первым, а `transactions.wallet_uid` ссылается на `wallets`: кошелька нет в шарде пользователя — INSERT падает на `transactions_wallet_uid_fkey`. `GlobalExceptionHandler` отвечает на этот constraint `404 WALLET_NOT_FOUND` (как person-service на индекс email); транзакция откатывается, `requestId` остаётся свободным |
| `payment_type` в SQL | тип — строкой в захвате `requestId` (`'DEPOSIT'`), свой запрос на операцию: ShardingSphere 5.5.3 переписывает `CAST(:type AS payment_type)` в сломанный SQL. Фильтр поиска сравнивает колонку как текст (`cast(type as varchar) = ?`): ShardingSphere передаёт параметр как `varchar`, а сравнения `payment_type = varchar` в PostgreSQL нет |
| Блокировки | confirm вывода и перевода читает кошелёк(и) `FOR NO KEY UPDATE`; перевод блокирует оба в порядке `ORDER BY uid`, поэтому два встречных перевода не попадают в deadlock. `NO KEY`, а не `FOR UPDATE`: INSERT claim-а через foreign key уже поставил на кошелёк `FOR KEY SHARE`, и `FOR UPDATE` ждал бы этот замок у параллельного claim — два confirm на один кошелёк попадали в deadlock. Блокировка защищает разные операции над одним кошельком, `requestId` — повторы одной и той же |
| Поиск | только строки пользователя (`user_uid` первым, поэтому один шард); каждый заданный фильтр добавляет одно условие (`TransactionSpecifications`); сначала новые, при равном времени решает `uid`, поэтому страницы не пересекаются. `Slice`, а не `Page`: контракт отдаёт просто массив без общего числа, `Page` зря гонял бы `count(*)` по всем подходящим строкам |
| Диапазон дат | `dateFrom` включительно, `dateTo` нет; `dateFrom` позже `dateTo` → `400 VALIDATION_ERROR` из `DateRangeValidator`, его вызывает сервис. Не ограничение на контроллере: Bean Validation запрещает ограничения на параметры метода, который реализует метод интерфейса, а контроллер реализует сгенерированный `TransactionsApi` |
| Баланс | никогда не отрицательный: `amount > 0`, проверка после fee, блокировка. `CHECK (balance >= 0)` в миграции — последняя защита |
| Пополнение | без проверки баланса; отказ провайдера (на карте не хватает, банк отказал) → `DepositFailed` |
| Мягкое удаление | каждое чтение кошелька идёт с `archived_at IS NULL` |
| Сбои | ошибка БД на любом шаге → откат, `500`, ничего не записано. Ответ потерялся после commit → клиент повторяет confirm с тем же `requestId` → `200` с той же транзакцией |
| Ошибки | тело RFC 9457 с `timestamp`, `error`, `traceId`, `details`, как в person-service |

### Контракт

`transaction-service/openapi/transaction-service.yaml`, генерация тем же
плагином и с теми же настройками, что в person-service: серверные интерфейсы
`WalletsApi`, `TransactionsApi` (их реализуют `WalletRestControllerV1`,
`TransactionRestControllerV1`) и реактивный `transaction-service-client`.
Avro-классы событий — из файлов `.avsc`, на следующем шаге.

| Пункт | Значение |
|---|---|
| Имена DTO | схемы ровно из ТЗ: `CreateWalletRequest`, `WalletResponse`, `DepositInitRequest`, `WithdrawalInitRequest`, `TransferInitRequest`, `TransactionInitResponse`, `DepositConfirmRequest`, `WithdrawalConfirmRequest`, `TransferConfirmRequest`, `TransactionConfirmResponse`, `TransactionStatusResponse`, `ErrorResponse`. У сгенерированных Java-классов суффикс `Dto` (`modelNameSuffix`), как в person-service: по HTTP ходит `WalletResponseDto`, сущность остаётся внутри |
| `TransactionInitResponse` | `amount`, `fee`, `total`, `requestId` |
| `TransactionConfirmResponse` | `transactionId`, `status` |
| `TransactionStatusResponse` | одинаковый в статусе и в поиске: `transactionId`, `type`, `status`, `walletUid`, `targetWalletUid`, `amount`, `fee`, `failureReason`, `createdAt` |
| Проверки `amount` | `> 0` из `exclusiveMinimum`; не больше 2 знаков — стандартная `@Digits(fraction = 2)`, ставится на поле через `x-field-extra-annotation` |
| `walletUid ≠ targetWalletUid` | своя аннотация класса `@DifferentWallets` с `ConstraintValidator` в пакете `validation`, ставится на `TransferInitRequest` и `TransferConfirmRequest` через `x-class-extra-annotation`. Без неё один кошелёк дважды читается как одна строка и дал бы `404` |
| `TransferWallets` | интерфейс с `getWalletUid()` и `getTargetWalletUid()`; оба DTO перевода реализуют его через `x-implements`, поэтому валидатор читает их, не зная сгенерированных классов, и одинаково работает в сервере и в jar клиента |
| Enum в параметрах запроса | `?type=deposit` переводится по значению из контракта через сгенерированный `EnumConverterConfiguration` (`fromValue`); `Enum.valueOf` знает только `DEPOSIT`. Неизвестное значение → `400 VALIDATION_ERROR` |
| Клиент | `openApiGenerateClient`: `spring-http-interface`, реактивный; свой source set `client` и jar `transaction-service-client` с API клиента, его DTO и `@DifferentWallets` с валидатором и `TransferWallets` (только эти файлы из `validation`: остальным нужны классы самого сервиса) |

### Сделано

- [x] Каркас модуля: `com.dezxxx.transaction`, Spring MVC, порт 8084, задачи
      unit- и интеграционных тестов, `gradlew` исполняемый
- [x] C4-диаграммы: контейнерная, компонентная и восемь sequence (пополнение,
      перевод, вывод, создание кошелька, кошелёк по id, кошельки пользователя,
      статус транзакции, поиск). Все ветки отказа, границы транзакций и что
      пишется в БД. В репозитории — английские
- [x] Ревью дизайна: все замечания учителя учтены в диаграммах; дизайн утверждён
- [x] Решения по контракту выше
- [x] Контракт `openapi/transaction-service.yaml`, генерация сервера и клиента,
      `@DifferentWallets` с unit-тестами. Swagger UI на `/swagger-ui.html` показывает сам файл контракта
      (springdoc, как в person-service); ещё не написанные эндпоинты отвечают `501`
- [x] `transaction-service-client` публикуется (`maven-publish`, как в person-service);
      в `Makefile` под `transaction-*`
- [x] Миграции V001-V002, ShardingSphere-JDBC на двух шардах PostgreSQL, Flyway
      на каждый шард; `ShardingIT` проверяет оба шарда, broadcast-таблицу, один шард
      на кошелёк, одновременный захват `requestId` и `CHECK (balance >= 0)`
- [x] `Dockerfile`, оба шарда в compose, `make verify` / `rebuild` собирают образ,
      Prometheus собирает `/actuator/prometheus`

- [x] Кошельки по диаграммам: создание, по id, кошельки пользователя. `WalletRestControllerV1` →
      `WalletService` → `WalletRepository`, `WalletTypeRepository`; `WalletMapper`;
      `GlobalExceptionHandler` (RFC 9457, как в person-service) с `TransactionServiceException`
      (у Spring уже есть `TransactionException`). Типы кошельков USD и EUR засеяны V003,
      uid оканчивается числовым кодом ISO 4217 (`…0840`, `…0978`). Трассировка по OTLP, как в person-service
- [x] Пополнение init и confirm по диаграмме: `TransactionRestControllerV1` → `TransactionService` →
      `TransactionRepository`, `TransactionEventRepository`, `WalletService`; `TransactionMapper`.
      confirm захватывает `requestId`, пишет транзакцию `PENDING` и неотправленное `DepositRequested`
      (payload в JSON, `traceparent`) в одной транзакции БД; повтор → `200`, другие условия → `409`
- [x] Kafka (KRaft) + Schema Registry в compose; Avro-события и jar `transaction-service-events`;
      `TransactionKafkaProducer` (отправляет `transaction_events`, продолжает трейс), `TransactionKafkaConsumer`
      (финализация, повторы, DLT); `TransactionKafkaIT` на настоящей Kafka
- [x] Fake Payment Provider: свой модуль, образ и цели `provider-*` в `Makefile`
- [x] Метрики, алерты и дашборд Grafana выше; `TransactionMetricsIT` проверяет сумму по обоим
      шардам и пулы шардов; `postgres_exporter` на каждый шард
- [x] Вывод init и confirm по диаграмме: init и confirm проверяют `balance >= amount + fee`
      (`400 INSUFFICIENT_FUNDS`); confirm читает кошелёк `FOR NO KEY UPDATE`, списывает `amount + fee` (резерв),
      пишет транзакцию `PENDING` и `WithdrawalRequested` в одной транзакции БД.
      `WithdrawalFailed` возвращает резерв, `WithdrawalCompleted` его оставляет. `TransactionWithdrawalIT`
      запускает два confirm на один кошелёк одновременно: один `200`, другой `400`, баланс не уходит в минус
- [x] Перевод init и confirm по диаграмме: оба кошелька пользователя, `ACTIVE` и в одной валюте
      (`400 CURRENCY_MISMATCH`); confirm блокирует оба `ORDER BY uid FOR NO KEY UPDATE`, списывает `amount + fee`,
      зачисляет `amount` и завершает транзакцию `COMPLETED` в одной транзакции БД, без Kafka.
      `TransactionTransferIT` запускает A→B и B→A одновременно: оба `200`, deadlock нет
- [x] Статус и поиск транзакций по диаграммам: статус своей транзакции (чужая → `404 TRANSACTION_NOT_FOUND`);
      поиск по кошельку, типу, статусу и датам, постранично, сначала новые. `TransactionStatusSearchIT`
      проверяет каждый фильтр, страницы без пересечений, чужой кошелёк → пустой список и ответы `400`
- [x] `postman/transaction-service.postman_collection.json`: кошельки Ивана, депозит (завершённый и один выше
      лимита провайдера, отклонённый), вывод, перевод, статус и каждый фильтр поиска, затем все ответы с ошибками;
      статусы депозита и вывода повторяют себя, пока `PENDING`. Каждый прогон создаёт новых пользователей.
      Swagger UI показывает сам файл контракта
- [x] Диаграммы совпадают с кодом: поиск сравнивает `type` как текст и читает `size + 1` строк (`Slice`),
      блокировки confirm читают только `wallets`, а валюту — после блокировки
- [x] transaction-service в карте конфигурации корневого `CONTEXT.ru.md`

### Дальше

- [ ] `transaction-service-client` в individuals-api, по диаграмме контейнеров: бины клиента,
      `/api/v1/wallets` и `/api/v1/transactions` с пользователем из токена
- [ ] Время запросов по шардам: `pg_stat_statements` в шардах не загружен, дашборд показывает
      время со стороны сервиса; счётчику full route нужен Java agent ShardingSphere
- [ ] Подписи в легендах Grafana выглядят как Java-имена (теги operation у `@Observed` в camelCase)
