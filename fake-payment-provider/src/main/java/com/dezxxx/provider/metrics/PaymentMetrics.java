package com.dezxxx.provider.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// the provider's answers; Prometheus adds _total: provider.payments is scraped as provider_payments_total
@Component
@RequiredArgsConstructor
public class PaymentMetrics {

    public static final String DEPOSIT = "deposit";

    public static final String WITHDRAWAL = "withdrawal";

    public static final String COMPLETED = "completed";

    public static final String FAILED = "failed";

    private final MeterRegistry registry;

    // two tags with two values each: four series at most; register returns the existing counter
    public void answered(String operation, String outcome) {
        Counter.builder("provider.payments")
                .description("Answers sent to transaction-service, by operation and outcome")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
