package com.dezxxx.individuals.integration.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Reads Keycloak from the outside, the way a human would check the admin
 * console, so an assertion is never made against the code that wrote the data.
 *
 * <p>Deliberately not built on {@code KeycloakAdminGateway}: a test that used
 * our own gateway to verify what our own gateway wrote would pass just as
 * happily if both sides were wrong in the same way. This talks to the Admin
 * REST API directly, as the realm's own administrator.
 */
public final class KeycloakAdminProbe {

    private static final String ADMIN_REALM = "master";

    /** Keycloak's built-in client for administrative logins. */
    private static final String ADMIN_CLIENT_ID = "admin-cli";

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;

    private final String realm;

    private final String adminUsername;

    private final String adminPassword;

    public KeycloakAdminProbe(String authServerUrl, String realm, String adminUsername, String adminPassword) {
        this.client = WebClient.builder().baseUrl(authServerUrl).build();
        this.realm = realm;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    /**
     * The account Keycloak holds for this email, or empty when there is none.
     * {@code exact=true} matters: without it Keycloak treats the value as an
     * infix search and a different account could answer.
     */
    public Optional<JsonNode> findUserByEmail(String email) {
        JsonNode users = parse(client.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParam("email", email)
                        .queryParam("exact", true)
                        .build(realm))
                .headers(headers -> headers.setBearerAuth(adminToken()))
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT));

        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    /**
     * Reads a single custom attribute. Keycloak returns every attribute as an
     * array, even the ones that only ever hold one value.
     */
    public Optional<String> attributeOf(JsonNode user, String name) {
        JsonNode values = user.path("attributes").path(name);
        return values.isArray() && !values.isEmpty()
                ? Optional.of(values.get(0).asText())
                : Optional.empty();
    }

    private String adminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", ADMIN_CLIENT_ID);
        form.add("username", adminUsername);
        form.add("password", adminPassword);

        JsonNode response = parse(client.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", ADMIN_REALM)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT));

        if (!response.hasNonNull("access_token")) {
            throw new IllegalStateException("Keycloak did not issue an admin token");
        }
        return response.get("access_token").asText();
    }

    /**
     * Bodies are taken as text and parsed here rather than decoded straight
     * into a {@code JsonNode}. Boot 4 configures the reactive codecs around its
     * own Jackson, which does not know this {@code JsonNode} type and answers
     * with a codec error that says nothing about the request - reading a String
     * keeps the parsing ours and the failure readable.
     */
    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body == null ? "{}" : body);
        } catch (JsonProcessingException cause) {
            throw new IllegalStateException("Keycloak answered something that is not JSON: " + body, cause);
        }
    }
}
