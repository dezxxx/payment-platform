package com.dezxxx.transaction.unit.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.metrics.TransactionMetrics;
import com.dezxxx.transaction.repository.OutboxEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionMetrics")
class TransactionMetricsTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private MeterRegistry registry;

    private TransactionMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TransactionMetrics(registry, outboxEventRepository, transactionRepository);
    }

    @Test
    @DisplayName("given unsent events and PENDING transactions, when refreshed, then the gauges show counts and ages in whole seconds")
    void refreshesGauges() {
        // given
        when(outboxEventRepository.countUnsent()).thenReturn(3L);
        when(outboxEventRepository.findOldestUnsentAgeSeconds()).thenReturn(Optional.of(120.4));
        when(transactionRepository.countPending()).thenReturn(5L);
        when(transactionRepository.findOldestPendingAgeSeconds()).thenReturn(Optional.of(600.6));

        // when
        metrics.refresh();

        // then
        assertThat(gauge("outbox.events.unsent")).isEqualTo(3);
        assertThat(gauge("outbox.events.unsent.oldest")).isEqualTo(120);
        assertThat(gauge("transaction.pending")).isEqualTo(5);
        assertThat(gauge("transaction.pending.oldest")).isEqualTo(601);
    }

    @Test
    @DisplayName("given nothing waits, when refreshed, then every gauge is 0")
    void zeroWhenNothingWaits() {
        // given
        when(outboxEventRepository.findOldestUnsentAgeSeconds()).thenReturn(Optional.empty());
        when(transactionRepository.findOldestPendingAgeSeconds()).thenReturn(Optional.empty());

        // when
        metrics.refresh();

        // then
        assertThat(gauge("outbox.events.unsent")).isZero();
        assertThat(gauge("outbox.events.unsent.oldest")).isZero();
        assertThat(gauge("transaction.pending")).isZero();
        assertThat(gauge("transaction.pending.oldest")).isZero();
    }

    @Test
    @DisplayName("given two failed sends, when counted, then the counter is 2")
    void countsSendFailures() {
        // when
        metrics.sendFailed();
        metrics.sendFailed();

        // then
        assertThat(registry.get("outbox.events.send.failures").counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("given dead letters from two topics, when counted, then one series per topic")
    void countsDeadLettersPerTopic() {
        // when
        metrics.deadLettered("deposit-completed");
        metrics.deadLettered("deposit-completed");
        metrics.deadLettered("withdrawal-failed");

        // then
        assertThat(deadLetters("deposit-completed")).isEqualTo(2);
        assertThat(deadLetters("withdrawal-failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("given a finished deposit and a failed withdrawal, when their transactions commit,"
            + " then one count per type and status")
    void countsFinishedAfterCommit() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        metrics.finishedAfterCommit(TransactionPaymentType.DEPOSIT, TransactionStatus.COMPLETED);
        metrics.finishedAfterCommit(TransactionPaymentType.WITHDRAWAL, TransactionStatus.FAILED);

        // when
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        // then
        assertThat(finished("DEPOSIT", "COMPLETED")).isEqualTo(1);
        assertThat(finished("WITHDRAWAL", "FAILED")).isEqualTo(1);
    }

    @Test
    @DisplayName("given a finished deposit, when its transaction rolls back, then nothing is counted")
    void rollbackIsNotCounted() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        metrics.finishedAfterCommit(TransactionPaymentType.DEPOSIT, TransactionStatus.COMPLETED);

        // when
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        // then
        assertThat(registry.find("transaction.finished").counter()).isNull();
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private double finished(String type, String status) {
        return registry.get("transaction.finished").tag("type", type).tag("status", status).counter().count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double deadLetters(String topic) {
        return registry.get("transaction.events.dead.letter").tag("topic", topic).counter().count();
    }
}
