package com.dezxxx.person.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.person.api.model.IndividualResponseDto;
import com.dezxxx.person.integration.support.IntegrationTest;
import com.dezxxx.person.integration.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

@DisplayName("Only a platform service gets in: the service token with the realm role SERVICE")
class ServiceAuthIT extends IntegrationTest {

    private static final String BY_EMAIL = "/api/v1/individuals/by-email?email={email}";

    private static final String COMPENSATION = "/api/v1/individuals/compensate-registration/{id}";

    @Test
    @DisplayName("given an individual, when its passport is read by email straight on the published port without a"
            + " token, then 401 AUTHENTICATION_REQUIRED")
    void refusesWithoutToken() {
        // given
        String email = freshEmail("auth-anonymous");
        createIndividual(email);

        // when / then
        anonymousClient.get().uri(BY_EMAIL, email)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("given an individual, when a user's token reads it by email, then 403 ACCESS_DENIED")
    void refusesUserToken() {
        // given
        String email = freshEmail("auth-user");
        createIndividual(email);

        // when / then
        anonymousClient.get().uri(BY_EMAIL, email)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.user())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("given an individual, when a hard delete comes with a token signed by another key, then 401"
            + " and every row stays")
    void refusedHardDeleteKeepsTheRows() {
        // given
        IndividualResponseDto individual = createIndividual(freshEmail("auth-delete"));

        // when
        anonymousClient.delete().uri(COMPENSATION, individual.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.signedByAnotherKey())
                .exchange()
                .expectStatus().isUnauthorized();

        // then
        assertThat(rowsOf("person.individuals", "id", individual.getId())).isEqualTo(1);
        assertThat(rowsOf("person.users", "id", individual.getUser().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("given an individual, when the service token reads it by email, then 200")
    void letsTheServiceIn() {
        // given
        String email = freshEmail("auth-service");
        createIndividual(email);

        // when / then
        client.get().uri(BY_EMAIL, email).exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("given no token, when Prometheus or Docker reads the actuator, then 200: infrastructure stays open")
    void leavesTheActuatorOpen() {
        // when / then
        anonymousClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
        anonymousClient.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    }
}
