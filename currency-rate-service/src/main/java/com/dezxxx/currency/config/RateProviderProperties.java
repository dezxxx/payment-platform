package com.dezxxx.currency.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("currency.provider")
public record RateProviderProperties(Duration connectTimeout,
                                     Duration responseTimeout,
                                     Oxr oxr) {

    public record Oxr(String url, String appId) {
    }
}
