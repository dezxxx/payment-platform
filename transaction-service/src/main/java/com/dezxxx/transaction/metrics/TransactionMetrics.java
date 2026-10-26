package com.dezxxx.transaction.metrics;

import com.dezxxx.transaction.repository.TransactionEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// the platform's own meters: the transaction_events backlog, PENDING transactions, Kafka send failures, the DLT.
// Prometheus adds _total to counters and the unit to gauges: transaction.pending.oldest is transaction_pending_oldest_seconds
@Component
public class TransactionMetrics {

    private static final String SECONDS = "seconds";

    private final MeterRegistry registry;
    private final TransactionEventRepository transactionEventRepository;
    private final TransactionRepository transactionRepository;

    // refresh() fills them, a scrape only reads them
    private final AtomicLong unsentEvents = new AtomicLong();
    private final AtomicLong oldestUnsentSeconds = new AtomicLong();
    private final AtomicLong pendingTransactions = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();

    private final Counter sendFailures;

    public TransactionMetrics(MeterRegistry registry, TransactionEventRepository transactionEventRepository,
                              TransactionRepository transactionRepository) {
        this.registry = registry;
        this.transactionEventRepository = transactionEventRepository;
        this.transactionRepository = transactionRepository;
        Gauge.builder("transaction.events.unsent", unsentEvents, AtomicLong::get)
                .description("Events in transaction_events Kafka has not acknowledged yet")
                .register(registry);
        Gauge.builder("transaction.events.unsent.oldest", oldestUnsentSeconds, AtomicLong::get)
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
        this.sendFailures = Counter.builder("transaction.events.send.failures")
                .description("Sends Kafka did not acknowledge; the event stays unsent for the next run")
                .register(registry);
    }

    // reads both shards: these are the only queries without user_uid besides the producer's
    @Scheduled(fixedDelayString = "${transaction.metrics.refresh-interval}")
    public void refresh() {
        unsentEvents.set(transactionEventRepository.countUnsent());
        oldestUnsentSeconds.set(seconds(transactionEventRepository.findOldestUnsentAgeSeconds()));
        pendingTransactions.set(transactionRepository.countPending());
        oldestPendingSeconds.set(seconds(transactionRepository.findOldestPendingAgeSeconds()));
    }

    public void sendFailed() {
        sendFailures.increment();
    }

    // one series per source topic; register returns the existing counter on the next call
    public void deadLettered(String topic) {
        Counter.builder("transaction.events.dead.letter")
                .description("Messages the consumer gave up on and published to <topic>-dlt")
                .tag("topic", topic)
                .register(registry)
                .increment();
    }

    // empty when nothing waits
    private static long seconds(Optional<Double> age) {
        return age.map(Math::round).orElse(0L);
    }
}
