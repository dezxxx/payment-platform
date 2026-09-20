package com.dezxxx.individuals.unit.gateway.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.gateway.keycloak.KeycloakErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code describe()} picks the most informative text out of the two shapes
 * Keycloak answers with. It only ever reaches a log line, which is precisely
 * why it is worth pinning: a silent regression here shows up as an
 * investigation that has nothing to go on.
 */
@DisplayName("KeycloakErrorResponse")
class KeycloakErrorResponseTest {

    @Test
    @DisplayName("given the OIDC shape, when described, then the description wins over the code")
    void prefersTheDescription() {
        // given - what the token endpoint answers
        KeycloakErrorResponse body =
                new KeycloakErrorResponse("invalid_grant", "Invalid user credentials", null);

        // when / then - the code says a category, the description says what
        // actually happened
        assertThat(body.describe()).isEqualTo("Invalid user credentials");
    }

    @Test
    @DisplayName("given the Admin API shape, when described, then its single message is used")
    void fallsBackToTheAdminMessage() {
        // given - the Admin API sends no error code at all
        KeycloakErrorResponse body =
                new KeycloakErrorResponse(null, null, "User exists with same email");

        // when / then
        assertThat(body.describe()).isEqualTo("User exists with same email");
    }

    @Test
    @DisplayName("given only a code, when described, then the code is better than nothing")
    void fallsBackToTheCode() {
        // given
        KeycloakErrorResponse body = new KeycloakErrorResponse("invalid_request", null, null);

        // when / then
        assertThat(body.describe()).isEqualTo("invalid_request");
    }

    @Test
    @DisplayName("given nothing at all, when described, then it says so instead of printing null")
    void saysWhenThereIsNothingToSay() {
        // given - the failure carried no body, or one that could not be read
        // when / then
        assertThat(KeycloakErrorResponse.EMPTY.describe()).isEqualTo("no details");
    }
}
