package com.dezxxx.individuals.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.config.KeycloakProperties;
import com.dezxxx.individuals.config.PersonServiceProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

// Binding of both properties records: a missing or blank value must stop startup.
// A bare Spring context with only the binder - no web server, no Keycloak
class PropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties({KeycloakProperties.class, PersonServiceProperties.class})
    static class Binding {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class)
            .withPropertyValues(
                    "individuals.keycloak.base-url=http://keycloak:8080",
                    "individuals.keycloak.realm=payment-platform",
                    "individuals.keycloak.client-id=individuals-api",
                    "individuals.keycloak.client-secret=secret",
                    "individuals.person-service.base-url=http://person-service:8082");

    @Test
    @DisplayName("binds every value and falls back to a 5s response timeout")
    void bindsValidConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            KeycloakProperties keycloak = context.getBean(KeycloakProperties.class);
            assertThat(keycloak.clientSecret()).isEqualTo("secret");
            assertThat(keycloak.responseTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    @DisplayName("an empty client secret stops startup and names the property")
    void blankClientSecretFailsStartup() {
        runner.withPropertyValues("individuals.keycloak.client-secret=")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("clientSecret"));
    }

    @Test
    @DisplayName("a missing person-service address stops startup")
    void missingPersonServiceUrlFailsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(Binding.class)
                .withPropertyValues(
                        "individuals.keycloak.base-url=http://keycloak:8080",
                        "individuals.keycloak.realm=payment-platform",
                        "individuals.keycloak.client-id=individuals-api",
                        "individuals.keycloak.client-secret=secret")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("baseUrl"));
    }
}
