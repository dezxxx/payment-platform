package com.dezxxx.transaction.integration.support;

import static org.awaitility.Awaitility.await;

import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.api.model.DepositInitRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.api.model.TransferInitRequestDto;
import com.dezxxx.transaction.api.model.WalletResponseDto;
import com.dezxxx.transaction.api.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.api.model.WithdrawalInitRequestDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.export.otlp.enabled=false")
@AutoConfigureRestTestClient
// a real tracer: events get a traceparent and errors a traceId; only the OTLP exporter is off,
// the global export switch would also empty the propagator
@AutoConfigureTracing
@Import(TestTokens.class)
public abstract class IntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:18.2";

    protected static final String USER_UID_HEADER = "X-User-Uid";

    protected static final Duration KAFKA_WAIT = Duration.ofSeconds(30);

    protected static final UUID USD_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000840");

    protected static final PostgreSQLContainer SHARD_0 = new PostgreSQLContainer(POSTGRES_IMAGE);

    protected static final PostgreSQLContainer SHARD_1 = new PostgreSQLContainer(POSTGRES_IMAGE);

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    static {
        SHARD_0.start();
        SHARD_1.start();
        KAFKA.start();
        System.setProperty("TRANSACTION_SHARD_0_URL", SHARD_0.getJdbcUrl());
        System.setProperty("TRANSACTION_SHARD_1_URL", SHARD_1.getJdbcUrl());
        System.setProperty("TRANSACTION_DB_USER", SHARD_0.getUsername());
        System.setProperty("TRANSACTION_DB_PASSWORD", SHARD_0.getPassword());
    }

    // every call carries the service token, as individuals-api sends it; anonymousClient sends none
    protected RestTestClient client;

    protected RestTestClient anonymousClient;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MeterRegistry meterRegistry;

    @Autowired
    protected KafkaProperties kafkaProperties;

    @Autowired
    protected KafkaTemplate<Object, Object> kafkaTemplate;

    @DynamicPropertySource
    static void shards(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=system_props");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url", () -> "mock://transaction-service");
        registry.add("transaction.offer.secret", () -> "integration-test-secret");
    }

    @Autowired
    void clients(RestTestClient restTestClient) {
        anonymousClient = restTestClient;
        client = restTestClient.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.service())
                .build();
    }

    protected UUID createWallet(UUID user) {
        return post(user, "/api/v1/wallets", new CreateWalletRequestDto().name("Main USD").walletTypeUid(USD_TYPE))
                .expectStatus().isCreated()
                .expectBody(WalletResponseDto.class)
                .returnResult()
                .getResponseBody()
                .getUid();
    }

    protected String depositOffer(UUID user, UUID wallet, BigDecimal amount) {
        return offerToken(user, "/api/v1/transactions/deposit/init",
                new DepositInitRequestDto().walletUid(wallet).amount(amount));
    }

    protected RestTestClient.ResponseSpec confirmDeposit(UUID user, String offerToken) {
        return post(user, "/api/v1/transactions/deposit/confirm", new DepositConfirmRequestDto().offerToken(offerToken));
    }

    protected TransactionConfirmResponseDto confirmed(UUID user, UUID wallet, BigDecimal amount) {
        return ok(confirmDeposit(user, depositOffer(user, wallet, amount)));
    }

    protected String withdrawalOffer(UUID user, UUID wallet, BigDecimal amount) {
        return offerToken(user, "/api/v1/transactions/withdrawal/init",
                new WithdrawalInitRequestDto().walletUid(wallet).amount(amount));
    }

    protected RestTestClient.ResponseSpec confirmWithdrawal(UUID user, String offerToken) {
        return post(user, "/api/v1/transactions/withdrawal/confirm",
                new WithdrawalConfirmRequestDto().offerToken(offerToken));
    }

    protected TransactionConfirmResponseDto confirmedWithdrawal(UUID user, UUID wallet, BigDecimal amount) {
        return ok(confirmWithdrawal(user, withdrawalOffer(user, wallet, amount)));
    }

    protected String transferOffer(UUID user, UUID source, UUID target, BigDecimal amount) {
        return offerToken(user, "/api/v1/transactions/transfer/init",
                new TransferInitRequestDto().walletUid(source).targetWalletUid(target).amount(amount));
    }

    protected RestTestClient.ResponseSpec confirmTransfer(UUID user, String offerToken) {
        return post(user, "/api/v1/transactions/transfer/confirm", new TransferConfirmRequestDto().offerToken(offerToken));
    }

    protected TransactionConfirmResponseDto confirmedTransfer(UUID user, UUID source, UUID target, BigDecimal amount) {
        return ok(confirmTransfer(user, transferOffer(user, source, target, amount)));
    }

    protected TransactionConfirmResponseDto ok(RestTestClient.ResponseSpec confirm) {
        return confirm
                .expectStatus().isOk()
                .expectBody(TransactionConfirmResponseDto.class)
                .returnResult()
                .getResponseBody();
    }

    private String offerToken(UUID user, String uri, Object initRequest) {
        return post(user, uri, initRequest)
                .expectStatus().isOk()
                .expectBody(TransactionInitResponseDto.class)
                .returnResult()
                .getResponseBody()
                .getOfferToken();
    }

    protected void fund(UUID wallet, UUID user, BigDecimal balance) {
        jdbc.update("UPDATE wallets SET balance = ? WHERE uid = ? AND user_uid = ?", balance, wallet, user);
    }

    protected int transactions(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_uid = ?", Integer.class, user);
    }

    protected int events(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE user_uid = ?", Integer.class, user);
    }

    protected BigDecimal balance(UUID wallet, UUID user) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE uid = ? AND user_uid = ?",
                BigDecimal.class, wallet, user);
    }

    protected ConsumerRecord<String, Object> firstMessage(String topic, String key) {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties();
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, Object>> found = new ArrayList<>();
        try (KafkaConsumer<String, Object> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            await().atMost(KAFKA_WAIT).until(() -> {
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

    // 0 before the first finished transaction of this type and status registers the counter
    protected double finished(String type, String status) {
        Counter counter = meterRegistry.find("transaction.finished").tag("type", type).tag("status", status).counter();
        return counter == null ? 0 : counter.count();
    }

    protected RestTestClient.ResponseSpec post(UUID user, String uri, Object body) {
        return client.post().uri(uri)
                .header(USER_UID_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    protected static JdbcTemplate shard(PostgreSQLContainer container) {
        return new JdbcTemplate(new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword()));
    }
}
