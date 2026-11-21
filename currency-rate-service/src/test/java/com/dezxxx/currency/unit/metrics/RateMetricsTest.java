package com.dezxxx.currency.unit.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dezxxx.currency.metrics.RateMetrics;
import com.dezxxx.currency.repository.ConversionRateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateMetrics")
class RateMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-14T14:00:00Z");

    @Mock
    private ConversionRateRepository conversionRateRepository;

    private MeterRegistry registry;

    private RateMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RateMetrics(registry, conversionRateRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("given the newest rate began 2 hours ago and 12 valid pairs, when refreshed, then the gauges show 7200 s and 12")
    void refreshesGauges() {
        // given
        when(conversionRateRepository.findNewestBeginTime()).thenReturn(Optional.of(Instant.parse("2026-11-14T12:00:00Z")));
        when(conversionRateRepository.countValidPairs(NOW)).thenReturn(12L);

        // when
        metrics.refresh();

        // then
        assertThat(gauge("rate.newest.age")).isEqualTo(7200);
        assertThat(gauge("rate.valid.pairs")).isEqualTo(12);
    }

    @Test
    @DisplayName("given no rate at all, when refreshed, then the age is 0 and the valid pairs are 0")
    void refreshesEmptyTable() {
        // given
        when(conversionRateRepository.findNewestBeginTime()).thenReturn(Optional.empty());
        when(conversionRateRepository.countValidPairs(NOW)).thenReturn(0L);

        // when
        metrics.refresh();

        // then
        assertThat(gauge("rate.newest.age")).isZero();
        assertThat(gauge("rate.valid.pairs")).isZero();
    }

    @Test
    @DisplayName("given one succeeded and one failed update, when counted, then each result has 1 and the timer 2")
    void countsUpdates() {
        // given
        Timer.Sample succeeded = metrics.startUpdate();
        Timer.Sample failed = metrics.startUpdate();

        // when
        metrics.updateSucceeded(succeeded);
        metrics.updateFailed(failed);

        // then
        assertThat(registry.get("rate.updates").tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("rate.updates").tag("result", "failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("rate.update.duration").timer().count()).isEqualTo(2);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
