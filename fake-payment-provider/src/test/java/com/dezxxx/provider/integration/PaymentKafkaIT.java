package com.dezxxx.provider.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dezxxx.transaction.event.DepositCompleted;
import com.dezxxx.transaction.event.DepositFailed;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.EventTopics;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(properties = "management.tracing.export.otlp.enabled=false")
// a real tracer: the answer continues the trace of the request
@AutoConfigureTracing
@DisplayName("Fake Payment Provider against a real Kafka")
class PaymentKafkaIT {

    // the image compose runs
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    private static final Duration WAIT = Duration.ofSeconds(30);

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    static {
        KAFKA.start();
    }

    @Autowired
    private KafkaProperties kafkaProperties;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Confluent's in-memory registry: the serializer and the deserializer of this JVM share it
        registry.add("spring.kafka.properties.schema.registry.url", () -> "mock://fake-payment-provider");
    }

    // transaction-service creates the topics in the platform; here the test does
    @BeforeAll
    static void topics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(EventTopics.DEPOSIT_REQUESTED, EventTopics.WITHDRAWAL_REQUESTED,
                            EventTopics.DEPOSIT_COMPLETED, EventTopics.DEPOSIT_FAILED).stream()
                    .map(name -> new NewTopic(name, 1, (short) 1)).toList()).all().get();
        }
    }

    @Test
    @DisplayName("given a DepositRequested up to the limit, when it arrives, then DepositCompleted in the same trace")
    void answersCompleted() {
        // given
        UUID transactionId = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        // when
        request(transactionId, user, "100.00");

        // then
        ConsumerRecord<String, Object> answer = firstMessage(EventTopics.DEPOSIT_COMPLETED, transactionId.toString());
        assertThat(answer.value()).isEqualTo(new DepositCompleted(transactionId, user));
        assertThat(header(answer, "traceparent")).contains(TRACE_ID);
    }

    @Test
    @DisplayName("given a DepositRequested above the limit, when it arrives, then DepositFailed with the reason")
    void answersFailed() {
        // given
        UUID transactionId = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        // when
        request(transactionId, user, "10000.01");

        // then
        ConsumerRecord<String, Object> answer = firstMessage(EventTopics.DEPOSIT_FAILED, transactionId.toString());
        assertThat(answer.value())
                .isEqualTo(new DepositFailed(transactionId, user, "Not enough funds on the card"));
    }

    // as transaction-service sends it: keyed by the transaction, the confirm's trace in the one header.
    // A plain producer: the template's own observation would add a traceparent of a new trace
    private void request(UUID transactionId, UUID user, String amount) {
        Map<String, Object> config = kafkaProperties.buildProducerProperties();
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        ProducerRecord<String, Object> request = new ProducerRecord<>(EventTopics.DEPOSIT_REQUESTED,
                transactionId.toString(),
                new DepositRequested(transactionId, user, UUID.randomUUID(), new BigDecimal(amount), "USD"));
        request.headers().add("traceparent",
                ("00-" + TRACE_ID + "-b7ad6b7169203331-01").getBytes(StandardCharsets.UTF_8));
        try (KafkaProducer<String, Object> producer = new KafkaProducer<>(config)) {
            producer.send(request);
        }
    }

    // reads the topic from the start with its own group until the message with this key shows up
    private ConsumerRecord<String, Object> firstMessage(String topic, String key) {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties();
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, Object>> found = new ArrayList<>();
        try (KafkaConsumer<String, Object> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            await().atMost(WAIT).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(message -> {
                    if (key.equals(message.key())) {
                        found.add(message);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found.getFirst();
    }

    private static String header(ConsumerRecord<String, Object> message, String name) {
        Header header = message.headers().lastHeader(name);
        assertThat(header).as("header " + name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
