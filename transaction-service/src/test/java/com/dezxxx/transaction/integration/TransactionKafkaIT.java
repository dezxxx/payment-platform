package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.event.DepositCompleted;
import com.dezxxx.transaction.event.DepositFailed;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.EventTopics;
import com.dezxxx.transaction.event.WithdrawalCompleted;
import com.dezxxx.transaction.event.WithdrawalFailed;
import com.dezxxx.transaction.event.WithdrawalRequested;
import com.dezxxx.transaction.integration.support.IntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TransactionKafkaProducer and TransactionKafkaConsumer against a real Kafka")
class TransactionKafkaIT extends IntegrationTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static final BigDecimal FUNDS = new BigDecimal("150.00");

    @Test
    @DisplayName("given a confirmed deposit, when the producer runs, then DepositRequested in deposit-requested"
            + " with the confirm's trace in its headers, and sent_at is set")
    void sendsDepositRequested() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        TransactionConfirmResponseDto confirmed = confirmed(user, wallet, AMOUNT);
        String traceparent = jdbc.queryForObject("SELECT traceparent FROM outbox_events"
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
        await().atMost(KAFKA_WAIT).until(() -> jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM outbox_events"
                + " WHERE transaction_uid = ? AND user_uid = ?", Boolean.class, confirmed.getTransactionId(), user));
    }

    @Test
    @DisplayName("given a PENDING deposit, when DepositCompleted arrives, then COMPLETED and the wallet credited")
    void completesDeposit() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID transactionId = confirmed(user, wallet, AMOUNT).getTransactionId();

        // when
        kafkaTemplate.send(EventTopics.DEPOSIT_COMPLETED, transactionId.toString(),
                new DepositCompleted(transactionId, user));

        // then
        await().atMost(KAFKA_WAIT).until(() -> "COMPLETED".equals(status(transactionId, user)));
        assertThat(balance(wallet, user)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("given a completed deposit, when DepositCompleted comes again, then the wallet is credited once"
            + " and the deposit counted once")
    void repeatedAnswerCreditsOnce() {
        // given
        double finishedBefore = finished("DEPOSIT", "COMPLETED");
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID transactionId = confirmed(user, wallet, AMOUNT).getTransactionId();
        kafkaTemplate.send(EventTopics.DEPOSIT_COMPLETED, transactionId.toString(),
                new DepositCompleted(transactionId, user));
        await().atMost(KAFKA_WAIT).until(() -> "COMPLETED".equals(status(transactionId, user)));

        // when
        kafkaTemplate.send(EventTopics.DEPOSIT_COMPLETED, transactionId.toString(),
                new DepositCompleted(transactionId, user));

        // then
        await().during(Duration.ofSeconds(3)).atMost(KAFKA_WAIT)
                .until(() -> balance(wallet, user).compareTo(AMOUNT) == 0);
        assertThat(finished("DEPOSIT", "COMPLETED")).isEqualTo(finishedBefore + 1);
    }

    @Test
    @DisplayName("given a PENDING withdrawal, when DepositCompleted comes with its id, then it is in"
            + " deposit-completed-dlt, the withdrawal stays PENDING with the reserve, and WithdrawalFailed still"
            + " returns amount + fee")
    void answerOfAnotherTypeGoesToDlt() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, FUNDS);
        UUID transactionId = confirmedWithdrawal(user, wallet, AMOUNT).getTransactionId();

        // when
        kafkaTemplate.send(EventTopics.DEPOSIT_COMPLETED, transactionId.toString(),
                new DepositCompleted(transactionId, user));

        // then
        ConsumerRecord<String, Object> dead = firstMessage(EventTopics.DEPOSIT_COMPLETED + "-dlt",
                transactionId.toString());
        assertThat(((DepositCompleted) dead.value()).getTransactionId()).isEqualTo(transactionId);
        assertThat(status(transactionId, user)).isEqualTo("PENDING");
        assertThat(balance(wallet, user)).isEqualByComparingTo("49.00");

        kafkaTemplate.send(EventTopics.WITHDRAWAL_FAILED, transactionId.toString(),
                new WithdrawalFailed(transactionId, user, "The bank refused the transfer"));
        await().atMost(KAFKA_WAIT).until(() -> "FAILED".equals(status(transactionId, user)));
        assertThat(balance(wallet, user)).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("given a PENDING deposit, when DepositFailed arrives, then FAILED with the reason, balance untouched")
    void failsDeposit() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID transactionId = confirmed(user, wallet, AMOUNT).getTransactionId();

        // when
        kafkaTemplate.send(EventTopics.DEPOSIT_FAILED, transactionId.toString(),
                new DepositFailed(transactionId, user, "Not enough funds on the card"));

        // then
        await().atMost(KAFKA_WAIT).until(() -> "FAILED".equals(status(transactionId, user)));
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM transactions WHERE uid = ? AND user_uid = ?",
                String.class, transactionId, user)).isEqualTo("Not enough funds on the card");
        assertThat(balance(wallet, user)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("given a confirmed withdrawal, when the producer runs, then WithdrawalRequested in withdrawal-requested")
    void sendsWithdrawalRequested() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, FUNDS);
        UUID transactionId = confirmedWithdrawal(user, wallet, AMOUNT).getTransactionId();

        // when
        ConsumerRecord<String, Object> message = firstMessage(EventTopics.WITHDRAWAL_REQUESTED,
                transactionId.toString());

        // then
        WithdrawalRequested event = (WithdrawalRequested) message.value();
        assertThat(event.getTransactionId()).isEqualTo(transactionId);
        assertThat(event.getUserId()).isEqualTo(user);
        assertThat(event.getWalletUid()).isEqualTo(wallet);
        assertThat(event.getAmount()).isEqualByComparingTo("100.00");
        assertThat(event.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("given a PENDING withdrawal, when WithdrawalCompleted arrives, then COMPLETED and the debit stays")
    void completesWithdrawal() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, FUNDS);
        UUID transactionId = confirmedWithdrawal(user, wallet, AMOUNT).getTransactionId();

        // when
        kafkaTemplate.send(EventTopics.WITHDRAWAL_COMPLETED, transactionId.toString(),
                new WithdrawalCompleted(transactionId, user));

        // then
        await().atMost(KAFKA_WAIT).until(() -> "COMPLETED".equals(status(transactionId, user)));
        assertThat(balance(wallet, user)).isEqualByComparingTo("49.00");
    }

    @Test
    @DisplayName("given a PENDING withdrawal, when WithdrawalFailed arrives, then FAILED and amount + fee are back")
    void failsWithdrawal() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, FUNDS);
        UUID transactionId = confirmedWithdrawal(user, wallet, AMOUNT).getTransactionId();
        assertThat(balance(wallet, user)).isEqualByComparingTo("49.00");

        // when
        kafkaTemplate.send(EventTopics.WITHDRAWAL_FAILED, transactionId.toString(),
                new WithdrawalFailed(transactionId, user, "The bank refused the transfer"));

        // then
        await().atMost(KAFKA_WAIT).until(() -> "FAILED".equals(status(transactionId, user)));
        assertThat(balance(wallet, user)).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("given an unknown transaction, when DepositCompleted arrives, then without retries it is in deposit-completed-dlt and counted")
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
        await().atMost(KAFKA_WAIT).untilAsserted(() -> assertThat(meterRegistry.get("transaction.events.dead.letter")
                .tag("topic", EventTopics.DEPOSIT_COMPLETED).counter().count()).isGreaterThanOrEqualTo(1));
    }

    private static String header(ConsumerRecord<String, Object> message, String name) {
        Header header = message.headers().lastHeader(name);
        assertThat(header).as("header " + name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String traceId(String traceparent) {
        return traceparent.split("-")[1];
    }

    private String status(UUID transactionId, UUID user) {
        return jdbc.queryForObject("SELECT status FROM transactions WHERE uid = ? AND user_uid = ?",
                String.class, transactionId, user);
    }
}
