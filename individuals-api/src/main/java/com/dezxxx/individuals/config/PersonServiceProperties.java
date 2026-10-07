package com.dezxxx.individuals.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "individuals.person-service")
public record PersonServiceProperties(
        @NotBlank String baseUrl,
        @DefaultValue("5s") Duration responseTimeout) {
}
