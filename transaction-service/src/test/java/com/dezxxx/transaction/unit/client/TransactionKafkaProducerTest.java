package com.dezxxx.transaction.unit.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.client.TransactionKafkaProducer;
import com.dezxxx.transaction.config.KafkaEventProperties;
import com.dezxxx.transaction.entity.transaction.TransactionEventEntity;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.metrics.TransactionMetrics;
import com.dezxxx.transaction.repository.TransactionEventRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
// the tracing stubs serve every test, not each one uses all of them
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TransactionKafkaProducer")
class TransactionKafkaProducerTest {

    private static final KafkaEventProperties PROPERTIES = new KafkaEventProperties(4, Duration.ofSeconds(1), 100,
            Duration.ofSeconds(1), 3);

    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Mock
    private TransactionEventRepository transactionEventRepository;

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
        producer = new TransactionKafkaProducer(transactionEventRepository, transactionMapper, kafkaTemplate,
                PROPERTIES, tracer, propagator, transactionMetrics);
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
        TransactionEventEntity first = event();
        TransactionEventEntity second = event();
        givenUnsent(first, second);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        producer.sendUnsent();

        // then
        verify(kafkaTemplate).send(eq("deposit-requested"), eq(first.getTransactionUid().toString()), any());
        verify(kafkaTemplate).send(eq("deposit-requested"), eq(second.getTransactionUid().toString()), any());
        verify(transactionEventRepository).markSent(eq(first.getUid()), eq(first.getUserUid()), any());
        verify(transactionEventRepository).markSent(eq(second.getUid()), eq(second.getUserUid()), any());
    }

    @Test
    @DisplayName("given Kafka does not acknowledge, when the producer runs, then sent_at stays NULL, the failure counted and the batch stops")
    void brokerFailureStopsBatch() {
        // given
        TransactionEventEntity first = event();
        TransactionEventEntity second = event();
        givenUnsent(first, second);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        // when
        producer.sendUnsent();

        // then
        verify(transactionEventRepository, never()).markSent(any(), any(), any());
        verify(kafkaTemplate, never()).send(anyString(), eq(second.getTransactionUid().toString()), any());
        verify(transactionMetrics).sendFailed();
    }

    @Test
    @DisplayName("given a row that cannot be mapped, when the producer runs, then it stays unsent and the next one goes")
    void skipsUnmappableRow() {
        // given
        TransactionEventEntity broken = event();
        TransactionEventEntity good = event();
        when(transactionEventRepository.findUnsent(100)).thenReturn(List.of(broken, good));
        when(transactionMapper.toRequestedEvent(broken)).thenThrow(new IllegalStateException("bad payload"));
        when(transactionMapper.toRequestedEvent(good)).thenReturn(new DepositRequested());
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        producer.sendUnsent();

        // then
        verify(transactionEventRepository, never()).markSent(eq(broken.getUid()), any(), any());
        verify(transactionEventRepository).markSent(eq(good.getUid()), eq(good.getUserUid()), any());
    }

    private void givenUnsent(TransactionEventEntity... events) {
        when(transactionEventRepository.findUnsent(100)).thenReturn(List.of(events));
        when(transactionMapper.toRequestedEvent(any())).thenReturn(new DepositRequested());
    }

    private static TransactionEventEntity event() {
        TransactionEventEntity event = new TransactionEventEntity();
        event.setUid(UUID.randomUUID());
        event.setTransactionUid(UUID.randomUUID());
        event.setUserUid(UUID.randomUUID());
        event.setEventType("DepositRequested");
        event.setTraceparent(TRACEPARENT);
        return event;
    }
}
