package com.dezxxx.transaction.integration.support;

import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.WalletResponseDto;
import com.dezxxx.transaction.api.model.WithdrawalConfirmRequestDto;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
public abstract class IntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:18.2";

    protected static final String USER_UID_HEADER = "X-User-Uid";

    protected static final UUID USD_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000840");

    protected static final PostgreSQLContainer SHARD_0 = new PostgreSQLContainer(POSTGRES_IMAGE);

    protected static final PostgreSQLContainer SHARD_1 = new PostgreSQLContainer(POSTGRES_IMAGE);

    // the image compose runs
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    static {
        SHARD_0.start();
        SHARD_1.start();
        KAFKA.start();
        // the names sharding.yaml and application.yml read; in the container they come from the environment
        System.setProperty("TRANSACTION_SHARD_0_URL", SHARD_0.getJdbcUrl());
        System.setProperty("TRANSACTION_SHARD_1_URL", SHARD_1.getJdbcUrl());
        System.setProperty("TRANSACTION_DB_USER", SHARD_0.getUsername());
        System.setProperty("TRANSACTION_DB_PASSWORD", SHARD_0.getPassword());
    }

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void shards(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=system_props");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Confluent's in-memory registry: the serializer and the deserializer of this JVM share it
        registry.add("spring.kafka.properties.schema.registry.url", () -> "mock://transaction-service");
    }

    protected UUID createWallet(UUID user) {
        return post(user, "/api/v1/wallets", new CreateWalletRequestDto().name("Main USD").walletTypeUid(USD_TYPE))
                .expectStatus().isCreated()
                .expectBody(WalletResponseDto.class)
                .returnResult()
                .getResponseBody()
                .getUid();
    }

    protected TransactionConfirmResponseDto confirmed(UUID user, UUID requestId, UUID wallet, BigDecimal amount) {
        return confirm(user, requestId, wallet, amount)
                .expectStatus().isOk()
                .expectBody(TransactionConfirmResponseDto.class)
                .returnResult()
                .getResponseBody();
    }

    protected RestTestClient.ResponseSpec confirm(UUID user, UUID requestId, UUID wallet, BigDecimal amount) {
        return post(user, "/api/v1/transactions/deposit/confirm",
                new DepositConfirmRequestDto().requestId(requestId).walletUid(wallet).amount(amount));
    }

    protected TransactionConfirmResponseDto confirmedWithdrawal(UUID user, UUID requestId, UUID wallet,
                                                                BigDecimal amount) {
        return confirmWithdrawal(user, requestId, wallet, amount)
                .expectStatus().isOk()
                .expectBody(TransactionConfirmResponseDto.class)
                .returnResult()
                .getResponseBody();
    }

    protected RestTestClient.ResponseSpec confirmWithdrawal(UUID user, UUID requestId, UUID wallet,
                                                            BigDecimal amount) {
        return post(user, "/api/v1/transactions/withdrawal/confirm",
                new WithdrawalConfirmRequestDto().requestId(requestId).walletUid(wallet).amount(amount));
    }

    // money on a wallet without a deposit through Kafka
    protected void fund(UUID wallet, UUID user, BigDecimal balance) {
        jdbc.update("UPDATE wallets SET balance = ? WHERE uid = ? AND user_uid = ?", balance, wallet, user);
    }

    protected int transactions(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_uid = ?", Integer.class, user);
    }

    protected int events(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM transaction_events WHERE user_uid = ?", Integer.class, user);
    }

    protected BigDecimal balance(UUID wallet, UUID user) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE uid = ? AND user_uid = ?",
                BigDecimal.class, wallet, user);
    }

    protected RestTestClient.ResponseSpec post(UUID user, String uri, Object body) {
        return client.post().uri(uri)
                .header(USER_UID_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    // straight to one shard, past ShardingSphere: shows where a row really is
    protected static JdbcTemplate shard(PostgreSQLContainer container) {
        return new JdbcTemplate(new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword()));
    }
}
