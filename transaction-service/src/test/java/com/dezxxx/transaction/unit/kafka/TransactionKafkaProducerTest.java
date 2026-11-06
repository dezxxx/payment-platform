package com.dezxxx.transaction.unit.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.config.KafkaEventProperties;
import com.dezxxx.transaction.entity.transaction.OutboxEventEntity;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.kafka.TransactionKafkaProducer;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.metrics.TransactionMetrics;
import com.dezxxx.transaction.repository.OutboxEventRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TransactionKafkaProducer")
class TransactionKafkaProducerTest {

    private static final KafkaEventProperties PROPERTIES = new KafkaEventProperties(4, Duration.ofSeconds(1), 100,
            Duration.ofSeconds(1), 3);

    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private TransactionMapper transactionMapper;

    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    @Mock
    private TransactionMetrics transactionMetrics;

    private TransactionKafkaProducer producer;

    @BeforeEach
    void setUp() {
        producer = new TransactionKafkaProducer(outboxEventRepository, transactionMapper, kafkaTemplate,
                PROPERTIES, tracer, propagator, transactionMetrics, Clock.systemUTC());
        Span.Builder builder = mock(Span.Builder.class, RETURNS_SELF);
        Span span = mock(Span.class);
        when(builder.start()).thenReturn(span);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(propagator.<Map<String, String>>extract(any(), any())).thenReturn(builder);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));
    }

    @Test
    @DisplayName("given two unsent events, when the producer runs, then both go to deposit-requested and are marked sent")
    void sendsAndMarks() {
        // given
        OutboxEventEntity first = event();
        OutboxEventEntity second = event();
        givenUnsent(first, second);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        producer.sendUnsent();

        // then
        verify(kafkaTemplate).send(eq("deposit-requested"), eq(first.getTransactionUid().toString()), any());
        verify(kafkaTemplate).send(eq("deposit-requested"), eq(second.getTransactionUid().toString()), any());
        verify(outboxEventRepository).markSent(eq(first.getUid()), eq(first.getUserUid()), any());
        verify(outboxEventRepository).markSent(eq(second.getUid()), eq(second.getUserUid()), any());
    }

    @Test
    @DisplayName("given Kafka does not acknowledge, when the producer runs, then sent_at stays NULL, the failure counted and the batch stops")
    void brokerFailureStopsBatch() {
        // given
        OutboxEventEntity first = event();
        OutboxEventEntity second = event();
        givenUnsent(first, second);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        // when
        producer.sendUnsent();

        // then
        verify(outboxEventRepository, never()).markSent(any(), any(), any());
        verify(kafkaTemplate, never()).send(anyString(), eq(second.getTransactionUid().toString()), any());
        verify(transactionMetrics).sendFailed();
    }

    @Test
    @DisplayName("given send throws at once (Schema Registry down), when the producer runs, then sent_at stays NULL,"
            + " the failure counted and the batch stops")
    void synchronousSendFailureStopsBatch() {
        // given
        OutboxEventEntity first = event();
        OutboxEventEntity second = event();
        givenUnsent(first, second);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenThrow(new SerializationException("Schema Registry is not reachable"));

        // when
        producer.sendUnsent();

        // then
        verify(outboxEventRepository, never()).markSent(any(), any(), any());
        verify(kafkaTemplate, never()).send(anyString(), eq(second.getTransactionUid().toString()), any());
        verify(transactionMetrics).sendFailed();
    }

    @Test
    @DisplayName("given a row that cannot be mapped, when the producer runs, then it stays unsent and the next one goes")
    void skipsUnmappableRow() {
        // given
        OutboxEventEntity broken = event();
        OutboxEventEntity good = event();
        when(outboxEventRepository.findUnsent(100)).thenReturn(List.of(broken, good));
        when(transactionMapper.toRequestedEvent(broken)).thenThrow(new IllegalStateException("bad payload"));
        when(transactionMapper.toRequestedEvent(good)).thenReturn(new DepositRequested());
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        producer.sendUnsent();

        // then
        verify(outboxEventRepository, never()).markSent(eq(broken.getUid()), any(), any());
        verify(outboxEventRepository).markSent(eq(good.getUid()), eq(good.getUserUid()), any());
    }

    private void givenUnsent(OutboxEventEntity... events) {
        when(outboxEventRepository.findUnsent(100)).thenReturn(List.of(events));
        when(transactionMapper.toRequestedEvent(any())).thenReturn(new DepositRequested());
    }

    private static OutboxEventEntity event() {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setUid(UUID.randomUUID());
        event.setTransactionUid(UUID.randomUUID());
        event.setUserUid(UUID.randomUUID());
        event.setEventType("DepositRequested");
        event.setTraceparent(TRACEPARENT);
        return event;
    }
}
