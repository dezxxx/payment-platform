package com.dezxxx.transaction.kafka;

import com.dezxxx.transaction.config.KafkaEventProperties;
import com.dezxxx.transaction.entity.transaction.OutboxEventEntity;
import com.dezxxx.transaction.entity.transaction.OutboxEventType;
import com.dezxxx.transaction.event.EventTopics;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.metrics.TransactionMetrics;
import com.dezxxx.transaction.repository.OutboxEventRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// sends the unsent rows of outbox_events to Kafka. sent_at is set only after the broker acknowledges,
// so a failure leaves the row for the next run: at-least-once, the consumer side ignores a repeat
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionKafkaProducer {

    private static final String TRACEPARENT = "traceparent";

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxEventRepository outboxEventRepository;
    private final TransactionMapper transactionMapper;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final KafkaEventProperties kafkaEventProperties;
    private final Tracer tracer;
    private final Propagator propagator;
    private final TransactionMetrics transactionMetrics;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${transaction.kafka.send-interval}")
    public void sendUnsent() {
        for (OutboxEventEntity event : outboxEventRepository.findUnsent(kafkaEventProperties.batchSize())) {
            if (!send(event)) {
                return;
            }
        }
    }

    // "try": the span scope is opened only to be closed
    @SuppressWarnings("try")
    private boolean send(OutboxEventEntity event) {
        SpecificRecord record;
        try {
            record = transactionMapper.toRequestedEvent(event);
        } catch (RuntimeException ex) {
            log.error("Event {} of transaction {} cannot be mapped, it stays unsent", event.getUid(),
                    event.getTransactionUid(), ex);
            return true;
        }
        Span span = continueTrace(event.getTraceparent()).name("send " + event.getEventType()).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            kafkaTemplate.send(topic(event.getEventType()), event.getTransactionUid().toString(), record)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            outboxEventRepository.markSent(event.getUid(), event.getUserUid(), Instant.now(clock));
            log.info("Sent {} of transaction {}", event.getEventType(), event.getTransactionUid());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            span.error(ex);
            return false;
        } catch (ExecutionException | TimeoutException | KafkaException ex) {
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
            case OutboxEventType.DEPOSIT_REQUESTED -> EventTopics.DEPOSIT_REQUESTED;
            case OutboxEventType.WITHDRAWAL_REQUESTED -> EventTopics.WITHDRAWAL_REQUESTED;
            default -> throw new IllegalStateException("No topic for event type " + eventType);
        };
    }
}
