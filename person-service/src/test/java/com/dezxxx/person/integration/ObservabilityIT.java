package com.dezxxx.person.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.person.integration.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Health and metrics")
class ObservabilityIT extends IntegrationTest {

    @Test
    @DisplayName("given the service started, when asking for health, then UP")
    void healthIsUp() {
        // when / then
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("given an individual was created, when Prometheus scrapes, then the operation timer and the HTTP histogram are there")
    void exposesMetrics() {
        // given
        createIndividual(freshEmail("metrics"));

        // when
        String scrape = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(scrape)
                .contains("individual_operations_seconds_count{")
                .contains("operation=\"create\"")
                .contains("http_server_requests_seconds_bucket{")
                .contains("hikaricp_connections_active");
    }
}
