# CONTEXT - fake-payment-provider

Module 3. Shared rules, ports and how the platform is started: [`CONTEXT.md`](../CONTEXT.md).
The events, the topics and the whole deposit / withdrawal flow: [`transaction-service/CONTEXT.md`](../transaction-service/CONTEXT.md).

---

## 1. What it is

The stand-in for an external payment provider. transaction-service publishes `DepositRequested` and
`WithdrawalRequested`; this service reads them and answers `*Completed` or `*Failed`. No card is charged
and no bank is called: the answer depends only on the amount.

## 2. Decisions

| Decision | Value |
|---|---|
| Module | its own Gradle build and container (`fake-payment-provider`, port 8086), package `com.dezxxx.provider`; Spring MVC only for health and `/actuator/prometheus`, the work is Kafka |
| Classes | `client/PaymentKafkaConsumer` → `service/PaymentService` → `client/PaymentKafkaProducer`; `metrics/PaymentMetrics`; `config/KafkaConfig`, `config/ProviderProperties` |
| Contract | the `transaction-service-events` jar from Nexus: the Avro classes, `EventTopics`, `TrustedEvents`. The provider never defines an event of its own |
| Answer | amount up to `provider.approval-limit` (10000) → `DepositCompleted` / `WithdrawalCompleted`; above → `DepositFailed` "Not enough funds on the card" / `WithdrawalFailed` "The bank refused the payout". Every answer carries `transactionId` and `userId` from the request |
| Delivery | read and answer in one Kafka transaction (`transaction-id-prefix`): the offset is committed with the answer, so a crash never leaves an answer without its offset or the other way round. Exactly-once Kafka → Kafka; `read_committed` consumer |
| Topics | transaction-service creates them; the provider starts after it (`depends_on: service_healthy`) |
| Trace | the `traceparent` header of the request continues in the answer: one trace from confirm to finalize |
| Metrics | `provider_payments_total{operation, outcome}`; Kafka client metrics (lag of the `fake-payment-provider` group) from Spring Boot; Prometheus job `fake-payment-provider` |

## 3. Build and tests

```bash
make provider-test   # unit: PaymentService, PaymentKafkaConsumer
make provider-it     # PaymentKafkaIT against a real Kafka (Testcontainers), stack down
```

The events jar must be in the local Maven repository (`make transaction-publish-local`) or in Nexus
(`make transaction-publish`) before the build; the image build reads it from Nexus.
