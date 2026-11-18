package com.dezxxx.currency.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("currency.rates")
public record RatesProperties(Duration updateInterval,
                              Duration initialDelay,
                              Duration lifetime,
                              Duration lockAtMostFor,
                              Duration lockAtLeastFor,
                              Duration transactionTimeout,
                              Duration maxAge) {
}
