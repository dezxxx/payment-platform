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

/**
 * What every integration test shares: a real Keycloak, a stubbed
 * person-service, a collector standing in for Tempo, and an application wired
 * to all three.
 *
 * <p><b>The containers are started once for the whole run, not once per
 * class.</b> JUnit keeps one JVM for the task, and a static field initialised
 * here outlives any single test class, so a Keycloak that takes some seconds to
 * boot is paid for once. The trade is that state carries between classes -
 * every test that creates an account therefore uses an email of its own.
 *
 * <p>Nothing here is configured twice. The realm comes from the same
 * {@code realm-export.json} that docker-compose mounts, and the client secret
 * is read back out of that file rather than repeated in a test property: a
 * secret spelled in two places is a secret that will disagree with itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    protected static final String REALM = "payment-platform";

    protected static final String CLIENT_ID = "individuals-api";

    private static final String REALM_EXPORT = "realm/realm-export.json";

    /** Kept in step with {@code KEYCLOAK_IMAGE} in .env by hand - see CONTEXT §10. */
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.7.2";

    /** Keycloak's own HTTP port inside the container, before Docker maps it. */
    private static final int KEYCLOAK_HTTP_PORT = 8080;

    /**
     * Ready means "our realm answers", not "the process is up".
     *
     * <p>The container's own wait strategy polls {@code /health/ready}, which
     * Keycloak 26 does not serve unless health is enabled explicitly, and then
     * only on its management port - so the wait can only ever time out. Waiting
     * on the realm's discovery document is both available by default and a
     * stronger statement: it answers once the import has finished, so a test
     * can never race the realm into existence.
     *
     * <p>Five minutes, which is far more than a cold start needs on an idle
     * machine. The limit is not sized for the good case: a realm import on a
     * machine that is also running the compose stack, or a CI agent sharing a
     * host, is a different order of slow, and a timeout that only fits the
     * good case fails as flakiness rather than as a clear error.
     */
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

    /** Published by the test framework once the random port is bound. */
    @Value("${local.server.port}")
    private int port;

    /**
     * Bound to the real server rather than injected, so the response timeout is
     * ours to set: Keycloak's first token call has to fetch and cache the
     * realm's JWKS, comfortably slower than the five seconds a WebTestClient
     * waits by default.
     */
    protected WebTestClient client;

    @BeforeEach
    void bindTheClientToTheRunningServer() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Every address the application talks to is decided after the JVM starts,
     * because the OS picks the ports. {@code @DynamicPropertySource} is the only
     * hook that runs late enough to know them and early enough to configure the
     * context.
     */
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

    /** An address nobody has registered yet, so tests never collide. */
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
