# transaction-service (модуль 3): отклонения от ТЗ

Я прочитал ТЗ модуля 3 целиком (практику и теорию) и подготовил C4-диаграммы сервиса:

| Диаграмма | Файл |
|---|---|
| контейнерная | `transaction-service/diagrams-c4/png/ru/container.png` |
| компонентная | `transaction-service/diagrams-c4/png/ru/component.png` |
| sequence: пополнение | `transaction-service/diagrams-c4/png/ru/sequence-deposit.png` |
| sequence: перевод | `transaction-service/diagrams-c4/png/ru/sequence-transfer.png` |
| sequence: вывод | `transaction-service/diagrams-c4/png/ru/sequence-withdrawal.png` |
| sequence: создание кошелька | `transaction-service/diagrams-c4/png/ru/sequence-wallet-create.png` |
| sequence: кошелёк по id | `transaction-service/diagrams-c4/png/ru/sequence-wallet-get.png` |
| sequence: кошельки пользователя | `transaction-service/diagrams-c4/png/ru/sequence-wallets-of-user.png` |
| sequence: статус транзакции | `transaction-service/diagrams-c4/png/ru/sequence-transaction-status.png` |
| sequence: поиск транзакций | `transaction-service/diagrams-c4/png/ru/sequence-transactions-search.png` |

Sequence-диаграммы пополнения, перевода и вывода построены на диаграммах из ТЗ: участники и шаги
те же. На каждой диаграмме показаны ветки отказа, границы транзакций БД и что пишется в БД.
Ниже — места, где я считаю нужным отойти от ТЗ, и почему.

## Устройство сервиса и транзакции

| № | В ТЗ | У меня | Почему |
|---|---|---|---|
| 1 | `WalletService` — отдельный участник (`WalletServiceClient`), на схеме платформы Wallets Service — отдельный сервис со своей БД | кошельки внутри transaction-service, одна БД | списание, запись транзакции и событие должны фиксироваться одной транзакцией БД. В двух сервисах каждое списание стало бы распределённой операцией |
| 2 | вывод: `WalletService → DB: Debit`, потом отдельно `DB: PENDING` | одна транзакция БД: блокировка кошелька, списание, `PENDING`, событие | при сбое между двумя шагами деньги списаны, а транзакции нет |
| 3 | `confirm`: запись `PENDING` в БД, затем отдельно отправка в Kafka | событие пишется в таблицу `transaction_events` той же транзакцией, `TransactionKafkaProducer` отправляет его в Kafka | БД и Kafka нельзя записать атомарно. Kafka недоступна после commit — транзакция навсегда `PENDING`, при выводе деньги зависают; отправка до commit — внешняя система обработает транзакцию, которой нет |
| 4 | `DepositCompleted` появляется на диаграмме без отправителя | Fake Payment Provider со схемы платформы — имитация внешнего платёжного провайдера: читает `*Requested`, отвечает `*Completed` или `*Failed` | без него ответ не придёт и операция не завершится |
| 5 | у пополнения нет события неудачи | `DepositFailed` → `FAILED`, баланс не меняется | иначе неуспешное пополнение навсегда остаётся `PENDING` |
| 6 | повторная доставка: проверить, обработан ли `transactionId` | статус меняется только из `PENDING`, в одной транзакции с изменением баланса | Kafka доставляет at-least-once; повтор сообщения не должен зачислить или вернуть деньги второй раз |

## Шардирование

| № | В ТЗ | У меня | Почему |
|---|---|---|---|
| 7 | Postgres [n shards], пример ключа — `user_uid` | 2 шарда, ключ `user_uid` (`HASH_MOD`); `wallets`, `transactions`, `transaction_events` — по `user_uid`, `wallet_types` — копия на каждом шарде | все данные пользователя лежат в одном шарде: операция над его кошельками — локальная ACID-транзакция. Каждый запрос идёт с `user_uid`, чтобы не опрашивать все шарды |
| 8 | перевод между кошельками, «полностью синхронный» | вопрос к вам — см. «Вопрос по переводам» ниже | |
| 9 | ShardingSphere через Spring Boot Starter (теория) | ShardingSphere-JDBC как драйвер: `ShardingSphereDriver` + YAML с правилами | Spring Boot Starter удалён в ShardingSphere 5.3 |

### Вопрос по переводам (пункт 8)

Для сервиса транзакций правильно, чтобы перевод шёл с кошелька одного пользователя на кошелёк
**другого** пользователя. Но в ТЗ три требования, которые вместе не сходятся:

1. перевод между кошельками (`target_wallet_uid` — любой кошелёк);
2. перевод «полностью синхронный», одной ACID-транзакцией;
3. шардирование по `user_uid`: кошельки разных пользователей лежат на разных шардах.

Одной локальной транзакцией перевод между шардами не сделать. Сейчас на диаграммах перевод только
между кошельками одного пользователя (один шард).

Если нужны переводы между разными пользователями, предлагаю **сагу с компенсацией** через Kafka:
списание на шарде отправителя → зачисление на шарде получателя → при ошибке возврат денег
отправителю и `FAILED`. Минус: перевод становится асинхронным, клиент сначала получает `PENDING`.

Синхронная альтернатива — XA-транзакции ShardingSphere (двухфазный commit). На Spring Boot 3+ они
не работают из коробки, нужен отдельный модуль Jakarta Transactions; его ещё надо проверить на
нашем Spring Boot 4.1.

**Вопрос:** оставляем перевод только между своими кошельками или делаем переводы между
пользователями через сагу?

## Контракт и вызовы

| № | В ТЗ | У меня | Почему |
|---|---|---|---|
| 10 | список функций: `/transactions/{transactionId}/confirm`; контракт: `/transactions/{type}/confirm` | `/api/v1/transactions/{type}/confirm`, как в контракте | `init` ничего не создаёт, поэтому до `confirm` никакого `transactionId` нет |
| 11 | `userUid` приходит от клиента: `/wallets/user/{userUid}`, `?userUid=` | `user_uid` берёт individuals-api из токена; transaction-service проверяет, что кошелёк и транзакция принадлежат ему | иначе, подставив чужой `userUid`, можно читать чужие кошельки и транзакции |
| 12 | события в JSON, суммы как `100.0` | Avro + Schema Registry, суммы — `decimal` | Schema Registry отклоняет несовместимую схему, контракт событий не ломается молча. Сумма с плавающей точкой даёт ошибки округления |
| 13 | individuals-api вызывает сервис через Feign | сгенерированный из контракта реактивный HTTP-клиент `transaction-service-client`, как `person-service-client` в модуле 2 | individuals-api работает на WebFlux, а Feign блокирует поток event loop |
| 18 | `DepositCompleted`, `WithdrawalCompleted` / `WithdrawalFailed`: `transactionId`, `status`, … — без `userId` | ответы провайдера несут `userId` из запроса | шард выбирается по `user_uid`; без него consumer искал бы транзакцию во всех шардах, с ним — один запрос в один шард |
| 19 | `init` и `confirm` ничем не связаны; в теории — `requestId` при confirm как идемпотентный ключ | `init` возвращает `requestId` (ничего не сохраняя), клиент передаёт его в `confirm`; `confirm` сохраняет его в `transactions.request_id`, уникально в пределах `user_uid` | связь `init` и `confirm` в трассировке и логах; повторный `confirm` с тем же `requestId` возвращает уже созданную транзакцию, а не списывает второй раз |

## Стек и инфраструктура

| № | В ТЗ | У меня | Почему |
|---|---|---|---|
| 14 | Java 21, Spring Boot 3.5 | Java 25, Spring Boot 4.1 | тот же стек, что в модулях 1 и 2 |
| 15 | трассировка Brave / Zipkin (Spring Cloud Sleuth), логи через Promtail | OpenTelemetry (OTLP) → Alloy → Tempo и Loki | как в модулях 1 и 2; Sleuth больше не развивается, Promtail устарел |
| 16 | порт 8082 | 8084 | 8082 занят person-service, 8083 — Nexus |

## Имена

| № | В ТЗ | У меня | Почему |
|---|---|---|---|
| 17 | `client/KafkaProducer`, `client/KafkaConsumer` (на диаграммах — `TransactionServiceConsumer`) | `client/TransactionKafkaProducer`, `client/TransactionKafkaConsumer` | через Kafka идут только события транзакций; в библиотеке Kafka уже есть классы `KafkaProducer` и `KafkaConsumer`, одинаковые имена путаются в импортах |

---

Прошу принять на проверку и подтвердить. После подтверждения внесу эти решения в CONTEXT как
согласованные и начну писать код.

Сергей Затульский
