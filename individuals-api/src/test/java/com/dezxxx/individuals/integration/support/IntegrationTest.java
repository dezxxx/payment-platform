package com.dezxxx.individuals.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
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

    protected static final StubOtlpCollector OTLP_COLLECTOR = new StubOtlpCollector();

    static {
        KEYCLOAK.start();
        PERSON_SERVICE.start();
        OTLP_COLLECTOR.start();
    }

    @Value("${local.server.port}")
    private int port;

    protected WebTestClient client;

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
                  "lastName": "Ivanov"
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
