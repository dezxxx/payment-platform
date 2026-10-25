package com.dezxxx.transaction.unit.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.metrics.TransactionMetrics;
import com.dezxxx.transaction.repository.TransactionEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionMetrics")
class TransactionMetricsTest {

    @Mock
    private TransactionEventRepository transactionEventRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private MeterRegistry registry;

    private TransactionMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TransactionMetrics(registry, transactionEventRepository, transactionRepository);
    }

    @Test
    @DisplayName("given unsent events and PENDING transactions, when refreshed, then the gauges show counts and ages in whole seconds")
    void refreshesGauges() {
        // given
        when(transactionEventRepository.countUnsent()).thenReturn(3L);
        when(transactionEventRepository.findOldestUnsentAgeSeconds()).thenReturn(Optional.of(120.4));
        when(transactionRepository.countPending()).thenReturn(5L);
        when(transactionRepository.findOldestPendingAgeSeconds()).thenReturn(Optional.of(600.6));

        // when
        metrics.refresh();

        // then
        assertThat(gauge("transaction.events.unsent")).isEqualTo(3);
        assertThat(gauge("transaction.events.unsent.oldest")).isEqualTo(120);
        assertThat(gauge("transaction.pending")).isEqualTo(5);
        assertThat(gauge("transaction.pending.oldest")).isEqualTo(601);
    }

    @Test
    @DisplayName("given nothing waits, when refreshed, then every gauge is 0")
    void zeroWhenNothingWaits() {
        // given
        when(transactionEventRepository.findOldestUnsentAgeSeconds()).thenReturn(Optional.empty());
        when(transactionRepository.findOldestPendingAgeSeconds()).thenReturn(Optional.empty());

        // when
        metrics.refresh();

        // then
        assertThat(gauge("transaction.events.unsent")).isZero();
        assertThat(gauge("transaction.events.unsent.oldest")).isZero();
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
        assertThat(registry.get("transaction.events.send.failures").counter().count()).isEqualTo(2);
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

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double deadLetters(String topic) {
        return registry.get("transaction.events.dead.letter").tag("topic", topic).counter().count();
    }
}
