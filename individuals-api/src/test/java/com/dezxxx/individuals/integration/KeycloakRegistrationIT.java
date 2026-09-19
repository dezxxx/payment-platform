package com.dezxxx.individuals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.integration.support.IntegrationTest;
import com.dezxxx.individuals.integration.support.KeycloakAdminProbe;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * IT-KC-001, IT-KC-002 and IT-KC-003: registration and login against a real
 * Keycloak, through the real HTTP stack, with nothing of ours mocked.
 *
 * <p>What is stubbed is person-service, and only because module 1 does not
 * contain it. Everything the module is judged on - the admin calls, the
 * password write, the token request, the mapper that carries {@code user_uid}
 * into the token - runs for real against the realm that docker-compose imports.
 */
@DisplayName("Registration against a real Keycloak")
class KeycloakRegistrationIT extends IntegrationTest {

    private static final String REGISTRATION_PATH = "/api/v1/auth/registration";

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private static final String PASSWORD = "Str0ngPass!";

    /** The same bean the resource server uses for inbound tokens. */
    @Autowired
    private ReactiveJwtDecoder jwtDecoder;

    @Test
    @DisplayName("IT-KC-001: given an unknown email, when registering, then the account exists in the realm")
    void createsTheAccountInTheRealm() {
        // given
        String email = freshEmail("it-kc-001");
        KeycloakAdminProbe keycloak = keycloakAdmin();
        assertThat(keycloak.findUserByEmail(email)).isEmpty();

        // when
        TokenResponse tokens = register(email);

        // then
        assertThat(tokens.getAccessToken()).isNotBlank();
        assertThat(tokens.getRefreshToken()).isNotBlank();

        Optional<JsonNode> account = keycloak.findUserByEmail(email);
        assertThat(account).isPresent();
        assertThat(account.get().path("username").asText()).isEqualTo(email);
        assertThat(account.get().path("firstName").asText()).isEqualTo("Ivan");
        assertThat(account.get().path("lastName").asText()).isEqualTo("Ivanov");
        assertThat(account.get().path("enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("IT-KC-002: given a finished registration, when Keycloak is read, then the account carries user_uid")
    void stampsTheDomainIdentifierOnTheAccount() {
        // given
        String email = freshEmail("it-kc-002");
        KeycloakAdminProbe keycloak = keycloakAdmin();

        // when
        TokenResponse tokens = register(email);

        // then - the identifier person-service issued, not one we invented
        JsonNode account = keycloak.findUserByEmail(email).orElseThrow();
        assertThat(keycloak.attributeOf(account, "user_uid"))
                .contains(PERSON_SERVICE.userUid().toString());

        // and it reaches the client through the protocol mapper as well, which
        // is what lets /me answer without a second call to Keycloak
        assertThat(tokens.getUserUid()).isEqualTo(PERSON_SERVICE.userUid());
    }

    @Test
    @DisplayName("IT-KC-003: given a registered account, when logging in, then Keycloak issues a JWT we accept")
    void issuesAVerifiableTokenOnLogin() {
        // given
        String email = freshEmail("it-kc-003");
        register(email);

        // when
        TokenResponse tokens = client.post()
                .uri(LOGIN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "email": "%s", "password": "%s" }
                        """.formatted(email, PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult()
                .getResponseBody();

        // then - decoded by our own decoder, so the signature is checked
        // against the realm's JWKS rather than merely parsed
        assertThat(tokens).isNotNull();
        Jwt accessToken = jwtDecoder.decode(tokens.getAccessToken()).block();

        assertThat(accessToken).isNotNull();
        assertThat(accessToken.getIssuer().toString()).isEqualTo(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);
        assertThat(accessToken.getClaimAsString("email")).isEqualTo(email);
        assertThat(accessToken.getClaimAsString("user_uid")).isEqualTo(PERSON_SERVICE.userUid().toString());
        assertThat(accessToken.getExpiresAt()).isNotNull();
    }

    /**
     * The other half of UT-REG-002. {@code PasswordsMatchValidatorTest} proves
     * the rule fires; only a running application can show what the caller is
     * answered and that nothing downstream was touched - the request is
     * rejected by {@code @Valid} on the controller, so it never reaches a
     * service a unit test could mock.
     */
    @Test
    @DisplayName("UT-REG-002: given the confirmation does not match, then it answers 400 and Keycloak is never called")
    void rejectsAMismatchedConfirmationWithoutTouchingKeycloak() {
        // given
        String email = freshEmail("ut-reg-002");
        KeycloakAdminProbe keycloak = keycloakAdmin();

        // when
        client.post()
                .uri(REGISTRATION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationBody(email, PASSWORD, "Different1!"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details").value(details ->
                        assertThat(details.toString()).contains("confirmPassword: must match password"));

        // then - the realm holds no trace of the attempt, which is what
        // "Keycloak is never called" means from the outside
        assertThat(keycloak.findUserByEmail(email)).isEmpty();
    }

    private TokenResponse register(String email) {
        TokenResponse tokens = client.post()
                .uri(REGISTRATION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationBody(email, PASSWORD, PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TokenResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(tokens).isNotNull();
        return tokens;
    }
}
