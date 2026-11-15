package com.dezxxx.currency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.currency.integration.support.IntegrationTest;
import com.dezxxx.currency.integration.support.OxrStub;
import com.dezxxx.currency.metrics.RateMetrics;
import com.dezxxx.currency.scheduler.RateUpdateScheduler;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Hourly rate update")
class RateUpdateIT extends IntegrationTest {

    private static final String SERVER_ERROR = """
            {"error": true, "status": 500, "message": "server_error"}""";

    private static final String INVALID_APP_ID = """
            {"error": true, "status": 401, "message": "invalid_app_id"}""";

    @Autowired
    private RateUpdateScheduler scheduler;

    @Autowired
    private RateMetrics rateMetrics;

    @Test
    @DisplayName("given OXR answers, when the update runs, then 12 pairs are saved and OXR got the key in a header and only our currencies")
    void savesEveryPair() {
        // given
        double succeeded = updates("success");

        // when
        scheduler.updateRates();

        // then
        assertThat(rates()).isEqualTo(12);
        assertThat(rateOf("UAH", "EUR")).isEqualByComparingTo("0.01994710");
        assertThat(rateOf("USD", "EUR")).isEqualByComparingTo("0.90715000");
        assertThat(OXR.requests()).hasSize(1);
        OxrStub.Request request = OXR.requests().getFirst();
        assertThat(request.authorization()).isEqualTo("Token " + OXR_APP_ID);
        assertThat(request.query()).startsWith("symbols=").contains("USD", "EUR", "UAH", "ILS").doesNotContain(OXR_APP_ID);
        assertThat(updates("success")).isEqualTo(succeeded + 1);
    }

    @Test
    @DisplayName("given an update has just run, when it fires again, then ShedLock skips it: no second call, no new rows")
    void skipsWhileLocked() {
        // given
        scheduler.updateRates();

        // when
        scheduler.updateRates();

        // then
        assertThat(OXR.requests()).hasSize(1);
        assertThat(rates()).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT lock_until > timezone('utc', now()) FROM shedlock WHERE name = 'updateRates'",
                Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("given OXR answers 500 twice, when the update runs, then the third attempt saves the rates")
    void retriesServerError() {
        // given
        OXR.willAnswer(500, SERVER_ERROR);
        OXR.willAnswer(500, SERVER_ERROR);

        // when
        scheduler.updateRates();

        // then
        assertThat(OXR.requests()).hasSize(3);
        assertThat(rates()).isEqualTo(12);
    }

    @Test
    @DisplayName("given OXR answers 500 three times, when the update runs, then nothing is saved and the failure is counted")
    void givesUpAfterThreeAttempts() {
        // given
        double failed = updates("failure");
        OXR.willAnswer(500, SERVER_ERROR);
        OXR.willAnswer(500, SERVER_ERROR);
        OXR.willAnswer(500, SERVER_ERROR);

        // when
        scheduler.updateRates();

        // then
        assertThat(OXR.requests()).hasSize(3);
        assertThat(rates()).isZero();
        assertThat(updates("failure")).isEqualTo(failed + 1);
    }

    @Test
    @DisplayName("given OXR refuses the key with 401, when the update runs, then one call only: a retry would not fix the key")
    void doesNotRetryClientError() {
        // given
        double failed = updates("failure");
        OXR.willAnswer(401, INVALID_APP_ID);

        // when
        scheduler.updateRates();

        // then
        assertThat(OXR.requests()).hasSize(1);
        assertThat(rates()).isZero();
        assertThat(updates("failure")).isEqualTo(failed + 1);
    }

    @Test
    @DisplayName("given ILS switched off, when the update runs, then OXR is not asked for it and only 6 pairs are saved")
    void skipsInactiveCurrency() {
        // given
        jdbc.update("UPDATE currencies SET active = FALSE WHERE code = 'ILS'");

        // when
        scheduler.updateRates();

        // then
        assertThat(OXR.requests().getFirst().query()).doesNotContain("ILS");
        assertThat(rates()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversion_rates WHERE 'ILS' IN (source_code, destination_code)",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("given OXR leaves out a currency, when the update runs, then nothing is saved: one call, no retry")
    void refusesIncompleteAnswer() {
        // given
        double failed = updates("failure");
        OXR.willAnswer(200, """
                {"timestamp": 1794682806, "base": "USD", "rates": {"EUR": 0.90715, "UAH": 45.477786, "USD": 1}}""");

        // when
        scheduler.updateRates();

        // then
        assertThat(OXR.requests()).hasSize(1);
        assertThat(rates()).isZero();
        assertThat(updates("failure")).isEqualTo(failed + 1);
    }

    @Test
    @DisplayName("given OXR sends the headers but holds the body past the timeout, when the update runs, then three attempts and nothing saved")
    void timesOutOnSlowBody() {
        // given
        OXR.willAnswerSlowly(Duration.ofSeconds(3));
        OXR.willAnswerSlowly(Duration.ofSeconds(3));
        OXR.willAnswerSlowly(Duration.ofSeconds(3));

        // when
        scheduler.updateRates();

        // then
        assertThat(OXR.requests()).hasSize(3);
        assertThat(rates()).isZero();
    }

    @Test
    @DisplayName("given a valid rate and OXR down, when the update fails, then the old rate is still answered")
    void keepsOldRatesWhenUpdateFails() {
        // given
        Instant begin = Instant.now().minus(Duration.ofHours(1));
        insertRate("UAH", "EUR", "0.01990000", begin, begin.plus(Duration.ofHours(3)));
        OXR.willAnswer(500, SERVER_ERROR);
        OXR.willAnswer(500, SERVER_ERROR);
        OXR.willAnswer(500, SERVER_ERROR);

        // when
        scheduler.updateRates();

        // then
        client.get().uri("/api/v1/rates?from=UAH&to=EUR")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rate").isEqualTo(0.0199);
    }

    @Test
    @DisplayName("given the update has run, when individuals-api asks for ILS to UAH, then the computed rate comes back")
    void answersComputedRate() {
        // given
        scheduler.updateRates();

        // when, then
        client.get().uri("/api/v1/rates?from=ILS&to=UAH")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rate").isEqualTo(14.13782752)
                .jsonPath("$.providerCode").isEqualTo("OXR");
    }

    @Test
    @DisplayName("given the update has run, when the metrics refresh, then 12 valid pairs and the newest rate is seconds old")
    void refreshesGaugesFromTable() {
        // given
        scheduler.updateRates();

        // when
        rateMetrics.refresh();

        // then
        assertThat(meterRegistry.get("rate.valid.pairs").gauge().value()).isEqualTo(12);
        assertThat(meterRegistry.get("rate.newest.age").gauge().value()).isBetween(0.0, 60.0);
    }
}
