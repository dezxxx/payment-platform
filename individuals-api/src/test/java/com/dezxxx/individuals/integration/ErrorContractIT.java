package com.dezxxx.individuals.integration;

import com.dezxxx.individuals.integration.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

@DisplayName("Error contract")
class ErrorContractIT extends IntegrationTest {

    private static final String ME = "/api/v1/auth/me";

    private static final String LOGIN = "/api/v1/auth/login";

    @Test
    @DisplayName("given no token, when asking who am I, then it says a token is missing - not that a password is wrong")
    void namesTheMissingTokenRatherThanAPassword() {
        // given / when
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
    @DisplayName("given a broken token, when asking who am I, then it is our error body, not an empty 401")
    void answersABrokenTokenInOurShape() {
        // given / when
        client.get()
                .uri(ME)
                .header("Authorization", "Bearer not.a.jwt")
                .exchange()
                // then
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AUTHENTICATION_REQUIRED")
                .jsonPath("$.path").isEqualTo(ME)
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("given a path that does not exist, when it is called, then the answer is about the token, not the path")
    void treatsAnUnknownPathAsUnauthenticated() {
        // given / when
        client.get()
                .uri("/api/v1/auth/nothing-here")
                .exchange()
                // then
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("given a wrong password, when logging in, then the same status carries a different code")
    void keepsInvalidCredentialsForTheLoginFlow() {
        // given / when
        client.post()
                .uri(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "email": "%s", "password": "WrongPassword1!" }
                        """.formatted(freshEmail("error-contract")))
                .exchange()
                // then
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

    @Test
    @DisplayName("given no passport, phone or country, when registering, then 400 names each field")
    void requiresIndividualData() {
        // given
        String body = registrationBody(freshEmail("no-individual"), "Str0ngPass!", "Str0ngPass!")
                .replace("\"countryAlpha3\": \"UKR\",", "")
                .replace("\"passportNumber\": \"123456789\",", "")
                .replace("\"phoneNumber\": \"+380441234567\"", "");

        // when
        client.post()
                .uri("/api/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                // then
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[?(@ =~ /individual.passportNumber: .*/)]").exists()
                .jsonPath("$.details[?(@ =~ /individual.phoneNumber: .*/)]").exists()
                .jsonPath("$.details[?(@ =~ /address.countryAlpha3: .*/)]").exists();
    }
}
