package com.dezxxx.currency.metrics;

import com.dezxxx.currency.repository.ConversionRateRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RateMetrics {

    private static final String SECONDS = "seconds";

    private static final String RESULT = "result";

    private final MeterRegistry registry;
    private final ConversionRateRepository conversionRateRepository;
    private final Clock clock;

    // refresh() fills them, a scrape only reads them
    private final AtomicLong newestRateAgeSeconds = new AtomicLong();
    private final AtomicLong validPairs = new AtomicLong();

    private final Counter updateSucceeded;
    private final Counter updateFailed;
    private final Timer updateDuration;

    public RateMetrics(MeterRegistry registry, ConversionRateRepository conversionRateRepository, Clock clock) {
        this.registry = registry;
        this.conversionRateRepository = conversionRateRepository;
        this.clock = clock;
        Gauge.builder("rate.newest.age", newestRateAgeSeconds, AtomicLong::get)
                .description("How long ago the newest rate became valid, 0 when there is none yet")
                .baseUnit(SECONDS)
                .register(registry);
        Gauge.builder("rate.valid.pairs", validPairs, AtomicLong::get)
                .description("Currency pairs with a rate valid now; 0 means every GET /rates answers 404")
                .register(registry);
        this.updateSucceeded = Counter.builder("rate.updates")
                .description("Hourly rate updates by result")
                .tag(RESULT, "success")
                .register(registry);
        this.updateFailed = Counter.builder("rate.updates")
                .description("Hourly rate updates by result")
                .tag(RESULT, "failure")
                .register(registry);
        this.updateDuration = Timer.builder("rate.update.duration")
                .description("Time of one rate update: provider calls and the write")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${currency.metrics.refresh-interval}")
    public void refresh() {
        Instant now = clock.instant();
        newestRateAgeSeconds.set(conversionRateRepository.findNewestBeginTime()
                .map(begin -> Duration.between(begin, now).toSeconds())
                .orElse(0L));
        validPairs.set(conversionRateRepository.countValidPairs(now));
    }

    public Timer.Sample startUpdate() {
        return Timer.start(registry);
    }

    public void updateSucceeded(Timer.Sample sample) {
        updateSucceeded.increment();
        sample.stop(updateDuration);
    }

    public void updateFailed(Timer.Sample sample) {
        updateFailed.increment();
        sample.stop(updateDuration);
    }
}
