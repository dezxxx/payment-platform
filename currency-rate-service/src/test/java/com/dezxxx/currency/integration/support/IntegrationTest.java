package com.dezxxx.currency.integration.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestTokens.class)
public abstract class IntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:18.2";

    protected static final String OXR_APP_ID = "test-app-id";

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    protected static final OxrStub OXR = new OxrStub();

    static {
        POSTGRES.start();
        OXR.start();
    }

    // every call carries the service token, as individuals-api sends it; anonymousClient sends none
    protected RestTestClient client;

    protected RestTestClient anonymousClient;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("currency.provider.oxr.url", OXR::url);
        registry.add("currency.provider.oxr.app-id", () -> OXR_APP_ID);
        // the tests start every update themselves; the scheduled one would race with them
        registry.add("currency.rates.initial-delay", () -> "1000d");
        // the slow-body test waits three times this long, not three times 5 s
        registry.add("currency.provider.response-timeout", () -> "1s");
    }

    @Autowired
    void clients(RestTestClient restTestClient) {
        anonymousClient = restTestClient;
        client = restTestClient.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.service())
                .build();
    }

    @BeforeEach
    void cleanRatesAndLock() {
        jdbc.update("DELETE FROM conversion_rates");
        jdbc.update("DELETE FROM rate_corrections");
        // ShedLock caches the row in memory: a deleted row is never recreated, every next update is skipped;
        // usingDbTime keeps lock_until in UTC, a bare now() would be in the connection's time zone
        releaseLock();
        jdbc.update("UPDATE currencies SET active = TRUE");
        OXR.reset();
    }

    protected void releaseLock() {
        jdbc.update("UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 second'");
    }

    protected void insertRate(String source, String destination, String rate, Instant begin, Instant end) {
        jdbc.update("INSERT INTO conversion_rates (source_code, destination_code, rate, rate_begin_time, rate_end_time,"
                        + " provider_code) VALUES (?, ?, ?, ?, ?, 'OXR')",
                source, destination, new BigDecimal(rate), utc(begin), utc(end));
    }

    protected int rates() {
        return jdbc.queryForObject("SELECT count(*) FROM conversion_rates", Integer.class);
    }

    protected int ratesEndingAt(Instant end) {
        return jdbc.queryForObject("SELECT count(*) FROM conversion_rates WHERE rate_end_time = ?", Integer.class, utc(end));
    }

    protected BigDecimal rateOf(String source, String destination) {
        return jdbc.queryForObject("SELECT rate FROM conversion_rates WHERE source_code = ? AND destination_code = ?",
                BigDecimal.class, source, destination);
    }

    // timestamp columns hold UTC; a Timestamp would be shifted to the machine's time zone by the driver
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // 0 before the first update with this result registers the counter
    protected double updates(String result) {
        Counter counter = meterRegistry.find("rate.updates").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
