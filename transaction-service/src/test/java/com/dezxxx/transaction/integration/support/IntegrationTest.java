package com.dezxxx.transaction.integration.support;

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

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void shards(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=system_props");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url", () -> "mock://transaction-service");
        registry.add("transaction.offer.secret", () -> "integration-test-secret");
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
