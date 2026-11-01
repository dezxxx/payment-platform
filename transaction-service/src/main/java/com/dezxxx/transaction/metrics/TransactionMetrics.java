package com.dezxxx.transaction.metrics;

import com.dezxxx.transaction.repository.OutboxEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TransactionMetrics {

    private static final String SECONDS = "seconds";

    private final MeterRegistry registry;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionRepository transactionRepository;

    // refresh() fills them, a scrape only reads them
    private final AtomicLong unsentEvents = new AtomicLong();
    private final AtomicLong oldestUnsentSeconds = new AtomicLong();
    private final AtomicLong pendingTransactions = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();

    private final Counter sendFailures;

    public TransactionMetrics(MeterRegistry registry, OutboxEventRepository outboxEventRepository,
                              TransactionRepository transactionRepository) {
        this.registry = registry;
        this.outboxEventRepository = outboxEventRepository;
        this.transactionRepository = transactionRepository;
        Gauge.builder("outbox.events.unsent", unsentEvents, AtomicLong::get)
                .description("Events in outbox_events Kafka has not acknowledged yet")
                .register(registry);
        Gauge.builder("outbox.events.unsent.oldest", oldestUnsentSeconds, AtomicLong::get)
                .description("How long the oldest unsent event has been waiting, 0 when there is none")
                .baseUnit(SECONDS)
                .register(registry);
        Gauge.builder("transaction.pending", pendingTransactions, AtomicLong::get)
                .description("Transactions waiting for the provider's answer")
                .register(registry);
        Gauge.builder("transaction.pending.oldest", oldestPendingSeconds, AtomicLong::get)
                .description("How long the oldest PENDING transaction has been waiting, 0 when there is none")
                .baseUnit(SECONDS)
                .register(registry);
        this.sendFailures = Counter.builder("outbox.events.send.failures")
                .description("Sends Kafka did not acknowledge; the event stays unsent for the next run")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${transaction.metrics.refresh-interval}")
    public void refresh() {
        unsentEvents.set(outboxEventRepository.countUnsent());
        oldestUnsentSeconds.set(seconds(outboxEventRepository.findOldestUnsentAgeSeconds()));
        pendingTransactions.set(transactionRepository.countPending());
        oldestPendingSeconds.set(seconds(transactionRepository.findOldestPendingAgeSeconds()));
    }

    public void sendFailed() {
        sendFailures.increment();
    }

    public void deadLettered(String topic) {
        Counter.builder("transaction.events.dead.letter")
                .description("Messages the consumer gave up on and published to <topic>-dlt")
                .tag("topic", topic)
                .register(registry)
                .increment();
    }

    private static long seconds(Optional<Double> age) {
        return age.map(Math::round).orElse(0L);
    }
}
