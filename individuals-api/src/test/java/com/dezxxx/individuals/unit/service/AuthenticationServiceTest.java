package com.dezxxx.individuals.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.admin.KeycloakAdminGateway;
import com.dezxxx.individuals.gateway.keycloak.oidc.KeycloakOidcGateway;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.service.AuthenticationService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Everything after registration: {@code login}, {@code refresh} and
 * {@code /me}, with the two gateways and the decoder replaced by mocks.
 *
 * <p>The decoder is mocked rather than given a real key, and that is the point
 * of this class: the service never checks a password or a signature itself -
 * Keycloak did both already. What it does is read the {@code user_uid} claim
 * out of a token it trusts and decide what to do when that claim is not there.
 * Those decisions are what is tested.
 *
 * <p>Given / when / then throughout. On the reactive stack the middle and the
 * last step are one expression: nothing runs until {@code StepVerifier}
 * subscribes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService")
class AuthenticationServiceTest {

    private static final String EMAIL = "user@dezxxx.com";

    private static final String PASSWORD = "Str0ngP@ssw0rd";

    private static final String ACCESS_TOKEN = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.access";

    private static final String REFRESH_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.refresh";

    private static final String KEYCLOAK_USER_ID = "0b7a5f2c-19d4-4a8e-9c3b-77e1a0d4f5b6";

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final OffsetDateTime REGISTERED_AT =
            OffsetDateTime.of(2026, 3, 14, 9, 30, 0, 0, ZoneOffset.UTC);

    @Mock
    private KeycloakOidcGateway keycloakOidcGateway;

    @Mock
    private KeycloakAdminGateway keycloakAdminGateway;

    @Mock
    private AuthMetrics metrics;

    @Mock
    private ReactiveJwtDecoder jwtDecoder;

    @InjectMocks
    private AuthenticationService authenticationService;

    @Test
    @DisplayName("UT-LOG-001: given valid credentials, when logging in, then both tokens come back carrying user_uid")
    void returnsBothTokensOnSuccessfulLogin() {
        // given
        when(keycloakOidcGateway.login(EMAIL, PASSWORD)).thenReturn(Mono.just(tokensFromKeycloak()));
        when(jwtDecoder.decode(ACCESS_TOKEN)).thenReturn(Mono.just(accessToken()));

        // when / then
        StepVerifier.create(authenticationService.login(EMAIL, PASSWORD))
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
        verifyNoInteractions(keycloakAdminGateway);
    }

    @Test
    @DisplayName("UT-LOG-002: given a wrong password, when logging in, then it answers 401 and counts the failure")
    void answersUnauthorizedOnAWrongPassword() {
        // given - the gateway has already translated Keycloak's own
        // "400 invalid_grant" into our code; the service only passes it on
        when(keycloakOidcGateway.login(EMAIL, PASSWORD))
                .thenReturn(Mono.error(new ApiException(ErrorCode.INVALID_CREDENTIALS)));

        // when / then
        StepVerifier.create(authenticationService.login(EMAIL, PASSWORD))
                .expectErrorSatisfies(error -> {
                    ErrorCode code = ((ApiException) error).getErrorCode();
                    assertThat(code).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(code.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                })
                .verify();

        // then - nothing was decoded, because there was no token to decode
        verify(metrics).loginStarted();
        verify(metrics).loginFailed();
        verifyNoInteractions(jwtDecoder, keycloakAdminGateway);
    }

    @Test
    @DisplayName("UT-REF-001: given a valid refresh token, when refreshing, then a fresh pair comes back with user_uid")
    void returnsAFreshTokenOnRefresh() {
        // given
        when(keycloakOidcGateway.refresh(REFRESH_TOKEN)).thenReturn(Mono.just(tokensFromKeycloak()));
        when(jwtDecoder.decode(ACCESS_TOKEN)).thenReturn(Mono.just(accessToken()));

        // when / then
        StepVerifier.create(authenticationService.refresh(REFRESH_TOKEN))
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

    /**
     * Not a code of its own in the handout, but it pins a decision that is easy
     * to "fix" by mistake: there is no failure counter for refresh. An expired
     * or already-used refresh token is the normal end of a session, and a meter
     * for it would invite an alert that fires on people simply coming back
     * tomorrow.
     */
    @Test
    @DisplayName("given the refresh token is expired, when refreshing, then the failure is not counted")
    void doesNotCountARefusedRefresh() {
        // given
        when(keycloakOidcGateway.refresh(REFRESH_TOKEN))
                .thenReturn(Mono.error(new ApiException(ErrorCode.INVALID_CREDENTIALS)));

        // when / then
        StepVerifier.create(authenticationService.refresh(REFRESH_TOKEN))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS))
                .verify();

        // then
        verify(metrics).refreshStarted();
        verify(metrics, never()).loginFailed();
    }

    @Test
    @DisplayName("UT-ME-001: given a valid bearer token, when asking who am I, then every field of the answer is filled")
    void answersWhoTheCallerIs() {
        // given - the token is already verified by the security filter chain,
        // so seven of the eight fields cost nothing to read
        when(keycloakAdminGateway.findRegisteredAt(KEYCLOAK_USER_ID)).thenReturn(Mono.just(REGISTERED_AT));

        // when / then
        StepVerifier.create(authenticationService.currentUser(accessToken()))
                .assertNext(user -> {
                    assertThat(user.getUserUid()).isEqualTo(USER_UID);
                    assertThat(user.getKeycloakUserId()).isEqualTo(KEYCLOAK_USER_ID);
                    assertThat(user.getEmail()).isEqualTo(EMAIL);
                    assertThat(user.getFirstName()).isEqualTo("Ivan");
                    assertThat(user.getLastName()).isEqualTo("Ivanov");
                    assertThat(user.getEmailVerified()).isTrue();
                    assertThat(user.getRoles()).containsExactly("USER");
                    // The eighth is in no claim, which is the whole reason this
                    // endpoint costs one call to the Admin API.
                    assertThat(user.getRegisteredAt()).isEqualTo(REGISTERED_AT);
                })
                .verifyComplete();

        // then - nothing is decoded: the token arrived already verified
        verifyNoInteractions(jwtDecoder, keycloakOidcGateway);
    }

    /**
     * The claim is missing only when the realm's {@code user_uid} mapper is
     * gone, or the account was made outside our registration flow. Both are our
     * configuration faults, and the answer must say so rather than hand the
     * client a response with a required field left empty.
     */
    @Test
    @DisplayName("given a token without user_uid, when asking who am I, then it answers 500 rather than a half-filled user")
    void refusesATokenWithoutTheDomainIdentifier() {
        // given
        when(keycloakAdminGateway.findRegisteredAt(KEYCLOAK_USER_ID)).thenReturn(Mono.just(REGISTERED_AT));

        // when / then
        StepVerifier.create(authenticationService.currentUser(tokenWithoutUserUid()))
                .expectErrorSatisfies(error -> {
                    ErrorCode code = ((ApiException) error).getErrorCode();
                    assertThat(code).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(code.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                })
                .verify();
    }

    /**
     * Our own decoder rejecting our own freshly issued token means a realm key
     * rotated mid-flight or the clocks disagree. The caller did nothing wrong,
     * so this must not come back as 401 - it is ours.
     */
    @Test
    @DisplayName("given the issued token cannot be decoded, when logging in, then it is reported as our failure")
    void reportsAnUndecodableTokenAsOurOwnFault() {
        // given
        when(keycloakOidcGateway.login(EMAIL, PASSWORD)).thenReturn(Mono.just(tokensFromKeycloak()));
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.error(new JwtException("signature mismatch")));

        // when / then
        StepVerifier.create(authenticationService.login(EMAIL, PASSWORD))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR))
                .verify();

        // then
        verify(metrics).loginFailed();
    }

    /** What the OIDC token endpoint answers: no user_uid anywhere in it. */
    private static TokenResponse tokensFromKeycloak() {
        return new TokenResponse()
                .accessToken(ACCESS_TOKEN)
                .refreshToken(REFRESH_TOKEN)
                .expiresIn(300L)
                .tokenType("Bearer");
    }

    private static Jwt accessToken() {
        return jwt()
                .claim("user_uid", USER_UID.toString())
                .claim("realm_access", Map.of("roles", List.of("USER")))
                .build();
    }

    private static Jwt tokenWithoutUserUid() {
        return jwt().build();
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue(ACCESS_TOKEN)
                .header("alg", "RS256")
                .subject(KEYCLOAK_USER_ID)
                .claim("email", EMAIL)
                .claim("given_name", "Ivan")
                .claim("family_name", "Ivanov")
                .claim("email_verified", true)
                .issuedAt(Instant.parse("2026-03-14T09:30:00Z"))
                .expiresAt(Instant.parse("2026-03-14T09:35:00Z"));
    }
}
