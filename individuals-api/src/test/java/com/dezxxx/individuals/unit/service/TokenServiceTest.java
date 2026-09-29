package com.dezxxx.individuals.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.dezxxx.individuals.unit.service.ServiceTokens.accessToken;

import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.client.KeycloakClient;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.service.TokenService;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

// TokenService with mocks: login and refresh. The decoder is a mock - Keycloak
// already checked password and signature; we test what happens around user_uid
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenService")
class TokenServiceTest {

    private static final String EMAIL = "user@dezxxx.com";

    private static final String PASSWORD = "Str0ngP@ssw0rd";

    private static final String ACCESS_TOKEN = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.access";

    private static final String REFRESH_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.refresh";

    private static final String KEYCLOAK_USER_ID = "0b7a5f2c-19d4-4a8e-9c3b-77e1a0d4f5b6";

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    @Mock
    private KeycloakClient keycloakClient;

    @Mock
    private AuthMetrics metrics;

    @Mock
    private ReactiveJwtDecoder jwtDecoder;

    // a real but empty registry: the spans get their names, nothing records them
    @Spy
    private ObservationRegistry observationRegistry = ObservationRegistry.create();

    @InjectMocks
    private TokenService tokenService;

    @Test
    @DisplayName("UT-LOG-001: given valid credentials, when logging in, then both tokens come back carrying user_uid")
    void returnsBothTokensOnSuccessfulLogin() {
        // given
        when(keycloakClient.login(EMAIL, PASSWORD)).thenReturn(Mono.just(tokensFromKeycloak()));
        when(jwtDecoder.decode(ACCESS_TOKEN)).thenReturn(Mono.just(accessToken()));

        // when / then
        StepVerifier.create(tokenService.login(EMAIL, PASSWORD))
                .assertNext(tokens -> {
                    assertThat(tokens.getAccessToken()).isEqualTo(ACCESS_TOKEN);
                    assertThat(tokens.getRefreshToken()).isEqualTo(REFRESH_TOKEN);
                    // Keycloak's token endpoint knows nothing about user_uid -
                    // it reaches the answer only because the service reads the
                    // claim the realm's mapper wrote and stamps it on.
                    assertThat(tokens.getUserUid()).isEqualTo(USER_UID);
                })
                .verifyComplete();

        // then
        verify(metrics).loginStarted();
        verify(metrics, never()).loginFailed();
        verify(keycloakClient, never()).findRegisteredAt(anyString());
    }

    @Test
    @DisplayName("UT-LOG-002: given a wrong password, when logging in, then it answers 401 and counts the failure")
    void answersUnauthorizedOnAWrongPassword() {
        // given - the gateway has already translated Keycloak's own
        // "400 invalid_grant" into our code; the service only passes it on
        when(keycloakClient.login(EMAIL, PASSWORD))
                .thenReturn(Mono.error(new ApiException(ErrorCode.INVALID_CREDENTIALS)));

        // when / then
        StepVerifier.create(tokenService.login(EMAIL, PASSWORD))
                .expectErrorSatisfies(error -> {
                    ErrorCode code = ((ApiException) error).getErrorCode();
                    assertThat(code).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(code.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                })
                .verify();

        // then - nothing was decoded, because there was no token to decode
        verify(metrics).loginStarted();
        verify(metrics).loginFailed();
        verifyNoInteractions(jwtDecoder);
        verify(keycloakClient, never()).findRegisteredAt(anyString());
    }

    @Test
    @DisplayName("UT-REF-001: given a valid refresh token, when refreshing, then a fresh pair comes back with user_uid")
    void returnsAFreshTokenOnRefresh() {
        // given
        when(keycloakClient.refresh(REFRESH_TOKEN)).thenReturn(Mono.just(tokensFromKeycloak()));
        when(jwtDecoder.decode(ACCESS_TOKEN)).thenReturn(Mono.just(accessToken()));

        // when / then
        StepVerifier.create(tokenService.refresh(REFRESH_TOKEN))
                .assertNext(tokens -> {
                    assertThat(tokens.getAccessToken()).isEqualTo(ACCESS_TOKEN);
                    assertThat(tokens.getUserUid()).isEqualTo(USER_UID);
                })
                .verifyComplete();

        // then - refresh does not touch the login counters
        verify(metrics).refreshStarted();
        verify(metrics, never()).loginStarted();
        verify(metrics, never()).loginFailed();
    }

    // no failure counter for refresh on purpose: a dead refresh token is a normal
    // end of a session, not something to alert on
    @Test
    @DisplayName("given the refresh token is expired, when refreshing, then it says so rather than blaming a password")
    void namesTheDeadRefreshTokenRatherThanAPassword() {
        // given - Keycloak answers invalid_grant to a wrong password and to a
        // spent refresh token alike, so the gateway hands up the only code it
        // can justify
        when(keycloakClient.refresh(REFRESH_TOKEN))
                .thenReturn(Mono.error(new ApiException(ErrorCode.INVALID_CREDENTIALS)));

        // when / then - this call carried no password, so the answer must not
        // claim one was wrong
        StepVerifier.create(tokenService.refresh(REFRESH_TOKEN))
                .expectErrorSatisfies(error -> {
                    ErrorCode code = ((ApiException) error).getErrorCode();
                    assertThat(code).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
                    assertThat(code.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                })
                .verify();

        // then - still not counted as a failure: a session ending is not one
        verify(metrics).refreshStarted();
        verify(metrics, never()).loginFailed();
    }

    // Keycloak down is not a dead refresh token - must not become "log in again"
    @Test
    @DisplayName("given Keycloak is unreachable, when refreshing, then the outage keeps its own code")
    void leavesOtherFailuresAlone() {
        // given
        when(keycloakClient.refresh(REFRESH_TOKEN))
                .thenReturn(Mono.error(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE)));

        // when / then
        StepVerifier.create(tokenService.refresh(REFRESH_TOKEN))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .verify();
    }

    // our decoder rejecting Keycloak's fresh token is our problem, not a 401
    @Test
    @DisplayName("given the issued token cannot be decoded, when logging in, then it is reported as our failure")
    void reportsAnUndecodableTokenAsOurOwnFault() {
        // given
        when(keycloakClient.login(EMAIL, PASSWORD)).thenReturn(Mono.just(tokensFromKeycloak()));
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.error(new JwtException("signature mismatch")));

        // when / then
        StepVerifier.create(tokenService.login(EMAIL, PASSWORD))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR))
                .verify();

        // then
        verify(metrics).loginFailed();
    }

    @Test
    @DisplayName("given a just-registered account, when tokens are issued, then they carry user_uid and no login is counted")
    void issuesTokensForRegistrationWithoutCountingALogin() {
        // given
        when(keycloakClient.login(EMAIL, PASSWORD)).thenReturn(Mono.just(tokensFromKeycloak()));
        when(jwtDecoder.decode(ACCESS_TOKEN)).thenReturn(Mono.just(accessToken()));

        // when / then
        StepVerifier.create(tokenService.issueTokens(EMAIL, PASSWORD))
                .assertNext(tokens -> assertThat(tokens.getUserUid()).isEqualTo(USER_UID))
                .verifyComplete();

        // then - registration is not a login the user made
        verifyNoInteractions(metrics);
    }

    // what Keycloak's token endpoint answers: no user_uid in it
    private static TokenResponse tokensFromKeycloak() {
        return new TokenResponse()
                .accessToken(ACCESS_TOKEN)
                .refreshToken(REFRESH_TOKEN)
                .expiresIn(300L)
                .tokenType("Bearer");
    }
}
