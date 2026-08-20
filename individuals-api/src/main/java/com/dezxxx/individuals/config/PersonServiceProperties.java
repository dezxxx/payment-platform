package com.dezxxx.individuals.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Address of person-service, bound from {@code individuals.person-service.*}.
 *
 * <p>The service itself arrives in module 2. Until then the address resolves to
 * nothing and every call through it fails - which is expected, and is why the
 * registration flow treats a person-service failure as a dependency error
 * rather than as a bug.
 *
 * @param baseUrl         root of person-service, no trailing slash
 * @param responseTimeout how long a single call may take before it is failed
 */
@ConfigurationProperties(prefix = "individuals.person-service")
public record PersonServiceProperties(
        String baseUrl,
        @DefaultValue("5s") Duration responseTimeout) {
}
