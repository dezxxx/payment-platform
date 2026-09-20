package com.dezxxx.individuals.integration;

import com.dezxxx.individuals.integration.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * That every failure answers in one shape, and that two failures sharing a
 * status still tell the caller different things.
 *
 * <p>An integration test rather than a unit one because the interesting cases
 * are decided before a handler is ever chosen: the security filter chain
 * rejects them, and WebFlux offers no way to route that into an
 * {@code @ExceptionHandler}. Only a running application exercises that road.
 */
@DisplayName("Error contract")
class ErrorContractIT extends IntegrationTest {

    private static final String ME = "/api/v1/auth/me";

    private static final String LOGIN = "/api/v1/auth/login";

    @Test
    @DisplayName("given no token, when asking who am I, then it says a token is missing - not that a password is wrong")
    void namesTheMissingTokenRatherThanAPassword() {
        // given / when - no Authorization header at all
        client.get()
                .uri(ME)
                .exchange()
                // then
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AUTHENTICATION_REQUIRED")
                .jsonPath("$.message").isEqualTo("A valid access token is required")
                .jsonPath("$.path").isEqualTo(ME)
                .jsonPath("$.traceId").isNotEmpty()
                .jsonPath("$.details").isArray();
    }

    @Test
    @DisplayName("given a path that does not exist, when it is called, then the answer is about the token, not the path")
    void treatsAnUnknownPathAsUnauthenticated() {
        // given / when - nothing is mapped here, and it is not in the public
        // list either, so the filter chain stops it before routing
        client.get()
                .uri("/api/v1/auth/nothing-here")
                .exchange()
                // then - 401 rather than 404 on purpose: answering "no such
                // path" to an unauthenticated caller would map out the API for
                // anyone who asks
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("given a wrong password, when logging in, then the same status carries a different code")
    void keepsInvalidCredentialsForTheLoginFlow() {
        // given / when - here a password really was compared
        client.post()
                .uri(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "email": "%s", "password": "WrongPassword1!" }
                        """.formatted(freshEmail("error-contract")))
                .exchange()
                // then - the status is the same as above, and that is exactly
                // why the two need different codes
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_CREDENTIALS")
                .jsonPath("$.message").isEqualTo("Email or password is incorrect");
    }

    @Test
    @DisplayName("given a body that fails validation, when it is sent, then details name the field")
    void reportsTheOffendingFieldInDetails() {
        // given / when
        client.post()
                .uri("/api/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationBody(freshEmail("error-contract"), "Str0ngPass!", "Different1!"))
                .exchange()
                // then
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").isEqualTo("confirmPassword: must match password");
    }
}
