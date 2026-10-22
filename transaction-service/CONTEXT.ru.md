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
| перевод между любыми кошельками | перевод между кошельками одного пользователя (один шард, одна ACID-транзакция) | вопрос о переводах между пользователями у учителя (сага или XA) |

### Решения

| Решение | Значение |
|---|---|
| Веб-стек | Spring MVC, Spring Data JPA с Hibernate поверх ShardingSphere-JDBC — блокирующий, как person-service. Реактивный только сгенерированный `transaction-service-client`: он работает в individuals-api, а не в transaction-service |
| Шардирование | 2 шарда, ключ `user_uid` (`HASH_MOD`); `wallets`, `transactions`, `transaction_events` по `user_uid`, `wallet_types` — копия на каждом шарде. Каждый запрос идёт с `user_uid`, кроме выборки неотправленных событий producer-ом, которая намеренно читает оба шарда |
| Доставка | at-least-once. `TransactionKafkaProducer` (`@Scheduled`) читает `transaction_events WHERE sent_at IS NULL`, ставит `sent_at` только после подтверждения broker-а, при ошибке оставляет `NULL`, и следующий запуск повторяет. Слово «outbox» в именах не используется |
| Финализация | `TransactionKafkaConsumer` → `TransactionService.finalize(transactionId, userId)` одной транзакцией: `UPDATE transactions … WHERE status = 'PENDING'`; 1 строка — первая обработка (пополнение зачисляет, неуспешный вывод возвращает `amount + fee`); 0 строк — повтор (log INFO, ничего не меняется) или неизвестная транзакция (исключение). Исключение → откат, повтор с паузой (`DefaultErrorHandler`), затем DLT |
| Идемпотентность confirm | первая команда транзакции confirm занимает `requestId`: `INSERT … 'PENDING', :requestId ON CONFLICT (user_uid, request_id) DO NOTHING RETURNING uid`. 1 строка — запрос его занял; 0 строк — повтор: те же параметры → `200` с уже созданной транзакцией и её статусом, другие → `409`. Одновременный второй INSERT ждёт окончания первой транзакции; откат освобождает `requestId`. ShardingSphere 5.5.3 разбирает эту команду; одновременный случай ещё проверить на живых шардах |
| Проверка запроса | `@Valid` в контроллере до любой транзакции БД: обязательные поля, UUID, `amount > 0` и не больше 2 знаков после запятой; у перевода `walletUid ≠ targetWalletUid`. Ошибка → `400`, ничего не записано |
| Комиссия и баланс | fee считается после чтения кошелька и до проверки `balance < amount + fee` → `400`, в init и заново в confirm (условиям из init не доверяем). `fee` при вставке `NULL`, заполняется `UPDATE` в той же транзакции |
| Блокировки | confirm вывода и перевода читает кошелёк(и) `FOR UPDATE`; перевод блокирует оба в порядке `ORDER BY uid`, поэтому два встречных перевода не попадают в deadlock. `FOR UPDATE` защищает разные операции над одним кошельком, `requestId` — повторы одной и той же |
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
| Клиент | `openApiGenerateClient`: `spring-http-interface`, реактивный; свой source set `client` и jar `transaction-service-client` с API клиента, его DTO и пакетом `validation` |

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
      `@DifferentWallets` с unit-тестами

### Дальше

- [ ] Публикация `transaction-service-client` в Nexus
- [ ] Миграции, ShardingSphere-JDBC на двух шардах PostgreSQL; проверить
      `INSERT … ON CONFLICT DO NOTHING RETURNING` двумя одновременными вставками
- [ ] Кошельки, затем init / confirm каждой операции, по диаграммам
- [ ] Kafka (KRaft) + Schema Registry, Avro-события, producer, consumer, DLT;
      Fake Payment Provider
- [ ] Наблюдаемость со своими метриками (lag Kafka, outbox, DLT, PENDING), тесты,
      коллекция Postman; `transaction-service-client` в individuals-api
- [ ] transaction-service в `make`, compose и Prometheus; дописать его в порядок
      сборки и карту конфигурации корневого `CONTEXT.ru.md`
