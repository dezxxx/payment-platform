package com.dezxxx.individuals.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// person-service settings from individuals.person-service.*
@ConfigurationProperties(prefix = "individuals.person-service")
public record PersonServiceProperties(
        String baseUrl,
        @DefaultValue("5s") Duration responseTimeout) {
}
