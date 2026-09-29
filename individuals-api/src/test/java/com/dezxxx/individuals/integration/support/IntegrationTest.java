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

// Shared by all integration tests: real Keycloak, stubbed person-service, a
// stand-in for Tempo, and the app wired to them. Containers start once per run,
// so each test uses its own email. Realm and secret come from realm-export.json
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    protected static final String REALM = "payment-platform";

    protected static final String CLIENT_ID = "individuals-api";

    private static final String REALM_EXPORT = "realm/realm-export.json";

    // keep equal to KEYCLOAK_IMAGE in .env by hand
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.7.2";

    // Keycloak's port inside the container
    private static final int KEYCLOAK_HTTP_PORT = 8080;

    // ready = our realm answers (its discovery document), not just "process up";
    // 5 minutes because a busy machine imports the realm much slower
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

    // random port, known once the server is bound
    @Value("${local.server.port}")
    private int port;

    // our own client so we set the timeout: the first token call fetches the
    // realm keys and is slower than the default 5 seconds
    protected WebTestClient client;

    @BeforeEach
    void bindTheClientToTheRunningServer() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ports are picked by the OS at start; @DynamicPropertySource runs late
    // enough to know them and early enough to configure the app
    @DynamicPropertySource
    static void wireTheApplicationToTheContainers(DynamicPropertyRegistry registry) {
        registry.add("individuals.keycloak.base-url", KEYCLOAK::getAuthServerUrl);
        registry.add("individuals.keycloak.realm", () -> REALM);
        registry.add("individuals.keycloak.client-id", () -> CLIENT_ID);
        registry.add("individuals.keycloak.client-secret", IntegrationTest::clientSecretFromRealmExport);

        // The resource server validates inbound tokens against the same realm
        // that issued them; a mismatch here would fail every authenticated call
        // for a reason that looks nothing like configuration.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);

        registry.add("individuals.person-service.base-url", PERSON_SERVICE::baseUrl);
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", OTLP_COLLECTOR::tracesEndpoint);
    }

    protected static KeycloakAdminProbe keycloakAdmin() {
        return new KeycloakAdminProbe(
                KEYCLOAK.getAuthServerUrl(), REALM, KEYCLOAK.getAdminUsername(), KEYCLOAK.getAdminPassword());
    }

    // a fresh email, so tests never collide
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
