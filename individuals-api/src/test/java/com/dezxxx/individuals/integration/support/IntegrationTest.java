package com.dezxxx.individuals.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.wait.strategy.Wait;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    protected static final String REALM = "payment-platform";

    protected static final String CLIENT_ID = "individuals-api";

    private static final String REALM_EXPORT = "realm/realm-export.json";

    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.7.2";

    private static final int KEYCLOAK_HTTP_PORT = 8080;

    protected static final KeycloakContainer KEYCLOAK = new KeycloakContainer(KEYCLOAK_IMAGE)
            .withRealmImportFile(REALM_EXPORT)
            .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .forPort(KEYCLOAK_HTTP_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)));

    protected static final StubPersonService PERSON_SERVICE = new StubPersonService();

    protected static final StubTransactionService TRANSACTION_SERVICE = new StubTransactionService();

    protected static final StubCurrencyRateService CURRENCY_RATE_SERVICE = new StubCurrencyRateService();

    protected static final StubOtlpCollector OTLP_COLLECTOR = new StubOtlpCollector();

    protected static final String TRANSACTION_SERVICE_TIMEOUT = "1s";

    protected static final String CURRENCY_RATE_SERVICE_TIMEOUT = "1s";

    static {
        KEYCLOAK.start();
        PERSON_SERVICE.start();
        TRANSACTION_SERVICE.start();
        CURRENCY_RATE_SERVICE.start();
        OTLP_COLLECTOR.start();
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private CircuitBreakerRegistry breakers;

    protected WebTestClient client;

    // the context is shared: failures stubbed by one test must not leave a breaker open for the next
    @BeforeEach
    void closeTheBreakers() {
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @BeforeEach
    void bindTheClientToTheRunningServer() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @DynamicPropertySource
    static void wireTheApplicationToTheContainers(DynamicPropertyRegistry registry) {
        registry.add("individuals.keycloak.base-url", KEYCLOAK::getAuthServerUrl);
        registry.add("individuals.keycloak.realm", () -> REALM);
        registry.add("individuals.keycloak.client-id", () -> CLIENT_ID);
        registry.add("individuals.keycloak.client-secret", IntegrationTest::clientSecretFromRealmExport);

        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);

        registry.add("individuals.person-service.base-url", PERSON_SERVICE::baseUrl);
        registry.add("individuals.transaction-service.base-url", TRANSACTION_SERVICE::baseUrl);
        registry.add("individuals.transaction-service.response-timeout", () -> TRANSACTION_SERVICE_TIMEOUT);
        registry.add("individuals.currency-rate-service.base-url", CURRENCY_RATE_SERVICE::baseUrl);
        registry.add("individuals.currency-rate-service.response-timeout", () -> CURRENCY_RATE_SERVICE_TIMEOUT);
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", OTLP_COLLECTOR::tracesEndpoint);
    }

    protected static KeycloakAdminProbe keycloakAdmin() {
        return new KeycloakAdminProbe(
                KEYCLOAK.getAuthServerUrl(), REALM, KEYCLOAK.getAdminUsername(), KEYCLOAK.getAdminPassword());
    }

    protected static String freshEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@dezxxx.test";
    }

    protected static String registrationBody(String email, String password, String confirmation) {
        return """
                {
                  "email": "%s",
                  "password": "%s",
                  "confirmPassword": "%s",
                  "firstName": "Ivan",
                  "lastName": "Ivanov",
                  "address": {
                    "countryAlpha3": "UKR",
                    "city": "Kyiv",
                    "zipCode": "01001",
                    "addressLine": "1 Example St."
                  },
                  "individual": {
                    "passportNumber": "123456789",
                    "phoneNumber": "+380441234567"
                  }
                }
                """.formatted(email, password, confirmation);
    }

    private static String clientSecretFromRealmExport() {
        try (InputStream export = new ClassPathResource(REALM_EXPORT).getInputStream()) {
            for (JsonNode client : new ObjectMapper().readTree(export).path("clients")) {
                if (CLIENT_ID.equals(client.path("clientId").asText())) {
                    return client.path("secret").asText();
                }
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot read " + REALM_EXPORT, cause);
        }
        throw new IllegalStateException("No client " + CLIENT_ID + " in " + REALM_EXPORT);
    }
}
