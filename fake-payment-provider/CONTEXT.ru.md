# CONTEXT - fake-payment-provider

Модуль 3. Общие правила, порты и запуск платформы: [`CONTEXT.ru.md`](../CONTEXT.ru.md).
События, топики и весь поток пополнения / вывода: [`transaction-service/CONTEXT.ru.md`](../transaction-service/CONTEXT.ru.md).

---

## 1. Что это

Заглушка внешнего платёжного провайдера. transaction-service публикует `DepositRequested` и
`WithdrawalRequested`; этот сервис читает их и отвечает `*Completed` или `*Failed`. Карта не списывается,
банк не вызывается: ответ зависит только от суммы.

## 2. Решения

| Решение | Значение |
|---|---|
| Модуль | своя сборка Gradle и контейнер (`fake-payment-provider`, порт 8086), пакет `com.dezxxx.provider`; Spring MVC только для health и `/actuator/prometheus`, работа идёт через Kafka |
| Классы | `client/PaymentKafkaConsumer` → `service/PaymentService` → `client/PaymentKafkaProducer`; `metrics/PaymentMetrics`; `config/KafkaConfig`, `config/ProviderProperties` |
| Контракт | jar `transaction-service-events` из Nexus: Avro-классы, `EventTopics`, `TrustedEvents`. Своих событий провайдер не объявляет |
| Ответ | сумма до `provider.approval-limit` (10000) → `DepositCompleted` / `WithdrawalCompleted`; больше → `DepositFailed` "Not enough funds on the card" / `WithdrawalFailed` "The bank refused the payout". Каждый ответ несёт `transactionId` и `userId` из запроса |
| Доставка | чтение и ответ в одной транзакции Kafka (`transaction-id-prefix`): offset фиксируется вместе с ответом, поэтому падение не оставит ответ без offset-а и наоборот. Exactly-once Kafka → Kafka; консьюмер `read_committed` |
| Топики | создаёт transaction-service; провайдер стартует после него (`depends_on: service_healthy`) |
| Трейс | заголовок `traceparent` запроса продолжается в ответе: один трейс от confirm до finalize |
| Метрики | `provider_payments_total{operation, outcome}`; метрики Kafka-клиента (lag группы `fake-payment-provider`) даёт Spring Boot; job Prometheus `fake-payment-provider` |

## 3. Сборка и тесты

```bash
make provider-test   # unit: PaymentService, PaymentKafkaConsumer
make provider-it     # PaymentKafkaIT на настоящей Kafka (Testcontainers), стенд остановлен
```

Jar событий должен лежать в локальном Maven-репозитории (`make transaction-publish-local`) или в Nexus
(`make transaction-publish`) до сборки; сборка образа берёт его из Nexus.
