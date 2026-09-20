package com.dezxxx.individuals.unit.gateway.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.KeycloakErrorTranslator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.test.StepVerifier;

/**
 * Where Keycloak's idea of a failure is turned into ours.
 *
 * <p>This class had no test at all until JaCoCo said so, and it is the worst
 * place in the service to leave untested: every line of it runs only when
 * Keycloak refuses something, which is exactly when nobody is watching. The
 * integration tests never reach it - they exercise the paths where Keycloak
 * answers normally.
 *
 * <p>Each case is written out rather than folded into one parameterised table,
 * because what is worth recording is not the mapping but the reason for it -
 * and a table has nowhere to put a reason.
 */
@DisplayName("KeycloakErrorTranslator")
class KeycloakErrorTranslatorTest {

    /** The OAuth 2 shape, used by Keycloak's OIDC endpoints. */
    private static final String OIDC_BODY = """
            {"error": "%s", "error_description": "%s"}
            """;

    /** The Admin API answers with a single message and no error code. */
    private static final String ADMIN_BODY = """
            {"errorMessage": "%s"}
            """;

    @Test
    @DisplayName("given a wrong password, when translated, then it is our 401 rather than Keycloak's 400")
    void mapsInvalidGrantToInvalidCredentials() {
        // given - Keycloak calls a wrong password a bad request; our contract
        // calls it unauthorized, and the gateway is where that is decided
        ClientResponse response = json(HttpStatus.BAD_REQUEST,
                OIDC_BODY.formatted("invalid_grant", "Invalid user credentials"));

        // when / then
        expect(response, ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("given an account that was never finished, when translated, then it is still 401")
    void mapsAnUnfinishedAccountToInvalidCredentials() {
        // given - the same invalid_grant, a different description. Both mean
        // "these credentials do not work", and the difference is ours to fix,
        // not something to explain to the caller
        ClientResponse response = json(HttpStatus.BAD_REQUEST,
                OIDC_BODY.formatted("invalid_grant", "Account is not fully set up"));

        // when / then
        expect(response, ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("given our own client secret is wrong, when translated, then the caller is not blamed for it")
    void mapsAClientAuthFailureToOurOwnError() {
        // given - 401 from Keycloak, but about this service, not about the
        // person using it
        ClientResponse response = json(HttpStatus.UNAUTHORIZED,
                OIDC_BODY.formatted("invalid_client", "Invalid client credentials"));

        // when / then - 500, because the request was fine and our
        // configuration was not
        expect(response, ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("given Keycloak 26's wording for the same thing, when translated, then it lands on the same code")
    void matchesKeycloaksOwnSpellingOfAClientAuthFailure() {
        // given - the OAuth 2 spec says invalid_client; Keycloak 26 answers
        // unauthorized_client. Both are matched rather than trusting either
        ClientResponse response = json(HttpStatus.UNAUTHORIZED,
                OIDC_BODY.formatted("unauthorized_client", "Invalid client credentials"));

        // when / then
        expect(response, ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("given the address is taken, when translated, then it stays a conflict")
    void mapsConflictToUserAlreadyExists() {
        // given - the Admin API shape: no error code, only a message
        ClientResponse response = json(HttpStatus.CONFLICT,
                ADMIN_BODY.formatted("User exists with same email"));

        // when / then
        expect(response, ErrorCode.USER_ALREADY_EXISTS, HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("given the account is gone, when translated, then it stays not found")
    void mapsNotFound() {
        // given
        ClientResponse response = json(HttpStatus.NOT_FOUND, ADMIN_BODY.formatted("User not found"));

        // when / then
        expect(response, ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("given Keycloak itself broke, when translated, then it is reported as a dependency being down")
    void mapsServerErrorsToDependencyUnavailable() {
        // given
        ClientResponse response = json(HttpStatus.INTERNAL_SERVER_ERROR, ADMIN_BODY.formatted("boom"));

        // when / then - 503, so the caller knows a retry may work
        expect(response, ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("given a 4xx we did not foresee, when translated, then it is our bug and not the caller's")
    void mapsAnUnforeseen4xxToOurOwnError() {
        // given - a malformed request of ours would look like this. Blaming
        // the caller with a 400 would send them looking for a mistake they did
        // not make
        ClientResponse response = json(HttpStatus.BAD_REQUEST,
                ADMIN_BODY.formatted("Invalid user profile attribute"));

        // when / then
        expect(response, ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("given a failure with no body, when translated, then the status still decides")
    void survivesAnEmptyBody() {
        // given - a proxy or a timeout can strip the body; losing the status
        // with it would turn a conflict into a generic failure
        ClientResponse response = ClientResponse.create(HttpStatus.CONFLICT).build();

        // when / then
        expect(response, ErrorCode.USER_ALREADY_EXISTS, HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("given a body that is not JSON, when translated, then it does not fail on top of the failure")
    void survivesAnUnreadableBody() {
        // given - an HTML error page from something in front of Keycloak
        ClientResponse response = ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .body("<html>502 Bad Gateway</html>")
                .build();

        // when / then - the parse failure is swallowed, the status is not
        expect(response, ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * The status is asserted alongside the code, not instead of it: the code is
     * what the client reads, and the status is what decides whether a retry is
     * worth trying. A constant carrying the wrong one of the two would pass a
     * test that only checked the other.
     */
    private static void expect(ClientResponse response, ErrorCode expected, HttpStatusCode expectedStatus) {
        StepVerifier.create(KeycloakErrorTranslator.translate(response))
                .assertNext(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    ErrorCode actual = ((ApiException) error).getErrorCode();
                    assertThat(actual).isEqualTo(expected);
                    assertThat(actual.getStatus()).isEqualTo(expectedStatus);
                })
                .verifyComplete();
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
