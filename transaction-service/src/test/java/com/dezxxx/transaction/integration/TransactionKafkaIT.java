package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.event.DepositCompleted;
import com.dezxxx.transaction.event.DepositFailed;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.EventTopics;
import com.dezxxx.transaction.integration.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;

@DisplayName("TransactionKafkaProducer and TransactionKafkaConsumer against a real Kafka")
class TransactionKafkaIT extends IntegrationTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("given a confirmed deposit, when the producer runs, then DepositRequested in deposit-requested"
            + " with the confirm's trace in its headers, and sent_at is set")
    void sendsDepositRequested() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        TransactionConfirmResponseDto confirmed = confirmed(user, UUID.randomUUID(), wallet, AMOUNT);
        String traceparent = jdbc.queryForObject("SELECT traceparent FROM transaction_events"
                + " WHERE transaction_uid = ? AND user_uid = ?", String.class, confirmed.getTransactionId(), user);

        // when
        ConsumerRecord<String, Object> message = firstMessage(EventTopics.DEPOSIT_REQUESTED,
                confirmed.getTransactionId().toString());

        // then
        DepositRequested event = (DepositRequested) message.value();
        assertThat(event.getTransactionId()).isEqualTo(confirmed.getTransactionId());
        assertThat(event.getUserId()).isEqualTo(user);
        assertThat(event.getWalletUid()).isEqualTo(wallet);
        assertThat(event.getAmount()).isEqualByComparingTo("100.00");
        assertThat(event.getCurrency()).isEqualTo("USD");
        assertThat(traceId(header(message, "traceparent"))).isEqualTo(traceId(traceparent));
        await().atMost(WAIT).until(() -> jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM transaction_events"
                + " WHERE transaction_uid = ? AND user_uid = ?", Boolean.class, confirmed.getTransactionId(), user));
    }

    @Test
    @DisplayName("given a PENDING deposit, when DepositCompleted arrives, then COMPLETED and the wallet credited")
    void completesDeposit() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID transactionId = confirmed(user, UUID.randomUUID(), wallet, AMOUNT).getTransactionId();

        // when
        kafkaTemplate.send(EventTopics.DEPOSIT_COMPLETED, transactionId.toString(),
                new DepositCompleted(transactionId, user));

        // then
        await().atMost(WAIT).until(() -> "COMPLETED".equals(status(transactionId, user)));
        assertThat(balance(wallet, user)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("given a completed deposit, when DepositCompleted comes again, then the wallet is credited once")
    void repeatedAnswerCreditsOnce() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID transactionId = confirmed(user, UUID.randomUUID(), wallet, AMOUNT).getTransactionId();
        kafkaTemplate.send(EventTopics.DEPOSIT_COMPLETED, transactionId.toString(),
                new DepositCompleted(transactionId, user));
        await().atMost(WAIT).until(() -> "COMPLETED".equals(status(transactionId, user)));

        // when
        kafkaTemplate.send(EventTopics.DEPOSIT_COMPLETED, transactionId.toString(),
                new DepositCompleted(transactionId, user));

        // then
        await().during(Duration.ofSeconds(3)).atMost(WAIT)
                .until(() -> balance(wallet, user).compareTo(AMOUNT) == 0);
    }

    @Test
    @DisplayName("given a PENDING deposit, when DepositFailed arrives, then FAILED with the reason, balance untouched")
    void failsDeposit() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID transactionId = confirmed(user, UUID.randomUUID(), wallet, AMOUNT).getTransactionId();

        // when
        kafkaTemplate.send(EventTopics.DEPOSIT_FAILED, transactionId.toString(),
                new DepositFailed(transactionId, user, "Not enough funds on the card"));

        // then
        await().atMost(WAIT).until(() -> "FAILED".equals(status(transactionId, user)));
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM transactions WHERE uid = ? AND user_uid = ?",
                String.class, transactionId, user)).isEqualTo("Not enough funds on the card");
        assertThat(balance(wallet, user)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("given an unknown transaction, when DepositCompleted arrives, then after the retries it is in deposit-completed-dlt and counted")
    void unknownTransactionGoesToDlt() {
        // given
        UUID transactionId = UUID.randomUUID();

        // when
        kafkaTemplate.send(EventTopics.DEPOSIT_COMPLETED, transactionId.toString(),
                new DepositCompleted(transactionId, UUID.randomUUID()));

        // then
        ConsumerRecord<String, Object> dead = firstMessage(EventTopics.DEPOSIT_COMPLETED + "-dlt",
                transactionId.toString());
        assertThat(((DepositCompleted) dead.value()).getTransactionId()).isEqualTo(transactionId);
        await().atMost(WAIT).untilAsserted(() -> assertThat(meterRegistry.get("transaction.events.dead.letter")
                .tag("topic", EventTopics.DEPOSIT_COMPLETED).counter().count()).isGreaterThanOrEqualTo(1));
    }

    // reads the topic from the start with its own group until the message with this key shows up
    private ConsumerRecord<String, Object> firstMessage(String topic, String key) {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties();
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, Object>> found = new ArrayList<>();
        try (KafkaConsumer<String, Object> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            await().atMost(WAIT).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(message -> {
                    if (key.equals(message.key())) {
                        found.add(message);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found.getFirst();
    }

    private static String header(ConsumerRecord<String, Object> message, String name) {
        Header header = message.headers().lastHeader(name);
        assertThat(header).as("header " + name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    // 00-<traceId>-<spanId>-<flags>: the producer's span is a child, so only the traceId is the same
    private static String traceId(String traceparent) {
        return traceparent.split("-")[1];
    }

    private String status(UUID transactionId, UUID user) {
        return jdbc.queryForObject("SELECT status FROM transactions WHERE uid = ? AND user_uid = ?",
                String.class, transactionId, user);
    }

    private BigDecimal balance(UUID wallet, UUID user) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE uid = ? AND user_uid = ?",
                BigDecimal.class, wallet, user);
    }
}
