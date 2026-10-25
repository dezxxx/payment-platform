package com.dezxxx.transaction.client;

import com.dezxxx.transaction.config.KafkaEventProperties;
import com.dezxxx.transaction.entity.transaction.TransactionEventEntity;
import com.dezxxx.transaction.entity.transaction.TransactionEventType;
import com.dezxxx.transaction.event.EventTopics;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.metrics.TransactionMetrics;
import com.dezxxx.transaction.repository.TransactionEventRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// sends the unsent rows of transaction_events to Kafka. sent_at is set only after the broker acknowledges,
// so a failure leaves the row for the next run: at-least-once, the consumer side ignores a repeat
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionKafkaProducer {

    private static final String TRACEPARENT = "traceparent";

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final TransactionEventRepository transactionEventRepository;
    private final TransactionMapper transactionMapper;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final KafkaEventProperties kafkaEventProperties;
    private final Tracer tracer;
    private final Propagator propagator;
    private final TransactionMetrics transactionMetrics;

    @Scheduled(fixedDelayString = "${transaction.kafka.send-interval}")
    public void sendUnsent() {
        for (TransactionEventEntity event : transactionEventRepository.findUnsent(kafkaEventProperties.batchSize())) {
            if (!send(event)) {
                // Kafka is not answering: the rest of the batch would fail the same way
                return;
            }
        }
    }

    // false when the broker did not acknowledge; a row that cannot be mapped is skipped and stays unsent.
    // "try": the span scope is opened only to be closed
    @SuppressWarnings("try")
    private boolean send(TransactionEventEntity event) {
        SpecificRecord record;
        try {
            record = transactionMapper.toRequestedEvent(event);
        } catch (RuntimeException ex) {
            log.error("Event {} of transaction {} cannot be mapped, it stays unsent", event.getUid(),
                    event.getTransactionUid(), ex);
            return true;
        }
        // a child of the confirm request's trace: KafkaTemplate puts its traceparent into the message headers
        Span span = continueTrace(event.getTraceparent()).name("send " + event.getEventType()).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            kafkaTemplate.send(topic(event.getEventType()), event.getTransactionUid().toString(), record)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            transactionEventRepository.markSent(event.getUid(), event.getUserUid(), Instant.now());
            log.info("Sent {} of transaction {}", event.getEventType(), event.getTransactionUid());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            span.error(ex);
            return false;
        } catch (ExecutionException | TimeoutException ex) {
            span.error(ex);
            transactionMetrics.sendFailed();
            log.error("Kafka did not acknowledge {} of transaction {}, sent_at stays NULL until the next run",
                    event.getEventType(), event.getTransactionUid(), ex);
            return false;
        } finally {
            span.end();
        }
    }

    private Span.Builder continueTrace(String traceparent) {
        if (traceparent == null) {
            return tracer.spanBuilder();
        }
        return propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get);
    }

    private String topic(String eventType) {
        return switch (eventType) {
            case TransactionEventType.DEPOSIT_REQUESTED -> EventTopics.DEPOSIT_REQUESTED;
            case TransactionEventType.WITHDRAWAL_REQUESTED -> EventTopics.WITHDRAWAL_REQUESTED;
            default -> throw new IllegalStateException("No topic for event type " + eventType);
        };
    }
}
