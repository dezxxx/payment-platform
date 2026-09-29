package com.dezxxx.individuals.unit.gateway.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.config.KeycloakProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.client.KeycloakClient;
import com.dezxxx.individuals.metrics.AuthMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

// KeycloakClient without a real Keycloak: a fake exchange function answers
// every request. Covers the service-account token cache and the translation
// of Keycloak's errors into our codes.
@DisplayName("KeycloakClient")
class KeycloakClientTest {

    private static final KeycloakProperties PROPERTIES = new KeycloakProperties(
            "http://keycloak", "payment-platform", "individuals-api", "secret", Duration.ofSeconds(5));

    @Test
    @DisplayName("given a live token, when asked twice, then Keycloak is called once")
    void reusesALiveToken() {
        AtomicInteger calls = new AtomicInteger();
        KeycloakClient client = clientAnswering(calls, tokenBody("token-1", 300));

        StepVerifier.create(client.adminLogin()).expectNext("token-1").verifyComplete();
        StepVerifier.create(client.adminLogin()).expectNext("token-1").verifyComplete();

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("given a token about to expire, when asked again, then a fresh one is fetched")
    void refetchesATokenAboutToExpire() {
        AtomicInteger calls = new AtomicInteger();
        // 5 seconds of life is inside the 10-second safety margin - never reused
        KeycloakClient client = clientAnswering(calls, tokenBody("short-lived", 5));

        StepVerifier.create(client.adminLogin()).expectNext("short-lived").verifyComplete();
        StepVerifier.create(client.adminLogin()).expectNext("short-lived").verifyComplete();

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("given Keycloak failed once, when asked again, then the failure is not cached")
    void doesNotCacheAFailure() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .baseUrl(PROPERTIES.baseUrl())
                .exchangeFunction(request -> Mono.just(calls.incrementAndGet() == 1
                        ? ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()
                        : json(tokenBody("after-outage", 300))))
                .build();
        KeycloakClient client = new KeycloakClient(webClient, PROPERTIES, new AuthMetrics(new SimpleMeterRegistry()));

        StepVerifier.create(client.adminLogin()).expectError().verify();
        StepVerifier.create(client.adminLogin()).expectNext("after-outage").verifyComplete();

        assertThat(calls).hasValue(2);
    }

    // --- Keycloak errors -> our codes ---

    // OIDC shape and Admin API shape of Keycloak's error body
    private static final String OIDC_BODY = "{\"error\":\"%s\",\"error_description\":\"%s\"}";
    private static final String ADMIN_BODY = "{\"errorMessage\":\"%s\"}";

    @Test
    @DisplayName("given a wrong password, then it is our 401 rather than Keycloak's 400")
    void wrongPasswordIsUnauthorized() {
        expectFailure(error(HttpStatus.BAD_REQUEST, OIDC_BODY.formatted("invalid_grant", "Invalid user credentials")),
                ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("given an account that was never finished, then it is still 401")
    void unfinishedAccountIsUnauthorized() {
        expectFailure(error(HttpStatus.BAD_REQUEST, OIDC_BODY.formatted("invalid_grant", "Account is not fully set up")),
                ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("given our own client secret is wrong, then the caller is not blamed for it")
    void wrongClientSecretIsOurError() {
        expectFailure(error(HttpStatus.UNAUTHORIZED, OIDC_BODY.formatted("invalid_client", "Invalid client credentials")),
                ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("given Keycloak 26's wording for a bad client, then it lands on the same code")
    void keycloak26ClientWordingIsOurError() {
        expectFailure(error(HttpStatus.UNAUTHORIZED, OIDC_BODY.formatted("unauthorized_client", "Invalid client credentials")),
                ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("given the address is taken, then it stays a conflict")
    void takenAddressIsConflict() {
        expectFailure(error(HttpStatus.CONFLICT, ADMIN_BODY.formatted("User exists with same email")),
                ErrorCode.USER_ALREADY_EXISTS, HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("given the account is gone, then it stays not found")
    void missingAccountIsNotFound() {
        expectFailure(error(HttpStatus.NOT_FOUND, ADMIN_BODY.formatted("User not found")),
                ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("given Keycloak itself broke, then it is a dependency being down")
    void keycloakFailureIsDependencyUnavailable() {
        expectFailure(error(HttpStatus.INTERNAL_SERVER_ERROR, ADMIN_BODY.formatted("boom")),
                ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("given a 4xx we did not foresee, then it is our bug and not the caller's")
    void unforeseen4xxIsOurError() {
        expectFailure(error(HttpStatus.BAD_REQUEST, ADMIN_BODY.formatted("Invalid user profile attribute")),
                ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("given a failure with no body, then the status still decides")
    void emptyBodyKeepsTheStatus() {
        expectFailure(ClientResponse.create(HttpStatus.CONFLICT).build(),
                ErrorCode.USER_ALREADY_EXISTS, HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("given a body that is not JSON, then it does not fail on top of the failure")
    void nonJsonBodyKeepsTheStatus() {
        expectFailure(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                        .body("<html>502 Bad Gateway</html>")
                        .build(),
                ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
    }

    // logs in against a Keycloak that answers with the given failure;
    // checks both the code (what the client reads) and its status (whether to retry)
    private static void expectFailure(ClientResponse failure, ErrorCode expected, HttpStatus expectedStatus) {
        WebClient webClient = WebClient.builder()
                .baseUrl(PROPERTIES.baseUrl())
                .exchangeFunction(request -> Mono.just(failure))
                .build();
        KeycloakClient client = new KeycloakClient(webClient, PROPERTIES, new AuthMetrics(new SimpleMeterRegistry()));

        StepVerifier.create(client.login("user@dezxxx.com", "password"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    ErrorCode actual = ((ApiException) error).getErrorCode();
                    assertThat(actual).isEqualTo(expected);
                    assertThat(actual.getStatus()).isEqualTo(expectedStatus);
                })
                .verify();
    }

    private static ClientResponse error(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    // a client whose Keycloak always answers with the same token body
    private static KeycloakClient clientAnswering(AtomicInteger calls, String body) {
        WebClient webClient = WebClient.builder()
                .baseUrl(PROPERTIES.baseUrl())
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(json(body));
                })
                .build();
        return new KeycloakClient(webClient, PROPERTIES, new AuthMetrics(new SimpleMeterRegistry()));
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static String tokenBody(String accessToken, long expiresIn) {
        return "{\"access_token\":\"" + accessToken + "\",\"expires_in\":" + expiresIn
                + ",\"token_type\":\"Bearer\"}";
    }
}
