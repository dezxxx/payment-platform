package com.dezxxx.provider.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.dezxxx.provider.client.PaymentKafkaProducer;
import com.dezxxx.provider.config.ProviderProperties;
import com.dezxxx.provider.metrics.PaymentMetrics;
import com.dezxxx.provider.service.PaymentService;
import com.dezxxx.transaction.event.DepositCompleted;
import com.dezxxx.transaction.event.DepositFailed;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.WithdrawalCompleted;
import com.dezxxx.transaction.event.WithdrawalFailed;
import com.dezxxx.transaction.event.WithdrawalRequested;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService")
class PaymentServiceTest {

    private static final UUID TRANSACTION_UID = UUID.fromString("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID WALLET_UID = UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c");

    @Mock
    private PaymentKafkaProducer paymentKafkaProducer;

    private MeterRegistry registry;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        paymentService = new PaymentService(new ProviderProperties(new BigDecimal("10000")), paymentKafkaProducer,
                new PaymentMetrics(registry));
    }

    @Test
    @DisplayName("given a deposit up to the limit, when processed, then DepositCompleted with the user to deposit-completed, counted")
    void completesDeposit() {
        // when
        paymentService.processDeposit(depositRequested("10000.00"));

        // then
        SpecificRecord answer = sent("deposit-completed");
        assertThat(answer).isEqualTo(new DepositCompleted(TRANSACTION_UID, USER_UID));
        assertThat(answers("deposit", "completed")).isEqualTo(1);
    }

    @Test
    @DisplayName("given a deposit above the limit, when processed, then DepositFailed: not enough funds on the card, counted")
    void declinesDeposit() {
        // when
        paymentService.processDeposit(depositRequested("10000.01"));

        // then
        SpecificRecord answer = sent("deposit-failed");
        assertThat(answer).isEqualTo(new DepositFailed(TRANSACTION_UID, USER_UID, "Not enough funds on the card"));
        assertThat(answers("deposit", "failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("given a withdrawal up to the limit, when processed, then WithdrawalCompleted to withdrawal-completed, counted")
    void completesWithdrawal() {
        // when
        paymentService.processWithdrawal(withdrawalRequested("50.00"));

        // then
        SpecificRecord answer = sent("withdrawal-completed");
        assertThat(answer).isEqualTo(new WithdrawalCompleted(TRANSACTION_UID, USER_UID));
        assertThat(answers("withdrawal", "completed")).isEqualTo(1);
    }

    @Test
    @DisplayName("given a withdrawal above the limit, when processed, then WithdrawalFailed: the bank refused, counted")
    void declinesWithdrawal() {
        // when
        paymentService.processWithdrawal(withdrawalRequested("20000.00"));

        // then
        SpecificRecord answer = sent("withdrawal-failed");
        assertThat(answer).isEqualTo(new WithdrawalFailed(TRANSACTION_UID, USER_UID, "The bank refused the payout"));
        assertThat(answers("withdrawal", "failed")).isEqualTo(1);
    }

    private double answers(String operation, String outcome) {
        return registry.get("provider.payments").tag("operation", operation).tag("outcome", outcome).counter().count();
    }

    private SpecificRecord sent(String topic) {
        ArgumentCaptor<SpecificRecord> answer = ArgumentCaptor.forClass(SpecificRecord.class);
        verify(paymentKafkaProducer).send(eq(topic), eq(TRANSACTION_UID), answer.capture());
        return answer.getValue();
    }

    private static DepositRequested depositRequested(String amount) {
        return new DepositRequested(TRANSACTION_UID, USER_UID, WALLET_UID, new BigDecimal(amount), "USD");
    }

    private static WithdrawalRequested withdrawalRequested(String amount) {
        return new WithdrawalRequested(TRANSACTION_UID, USER_UID, WALLET_UID, new BigDecimal(amount), "USD");
    }
}
