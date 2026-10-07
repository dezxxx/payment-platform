package com.dezxxx.individuals.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.dezxxx.individuals.unit.service.ServiceTokens.accessToken;
import static com.dezxxx.individuals.unit.service.ServiceTokens.tokenWithoutUserUid;

import com.dezxxx.individuals.api.model.RegistrationRequest;
import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.client.KeycloakClient;
import com.dezxxx.individuals.gateway.person.PersonClient;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.service.TokenService;
import com.dezxxx.individuals.service.UserService;
import io.micrometer.observation.ObservationRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

    private static final String EMAIL = "user@dezxxx.com";

    private static final String PASSWORD = "Str0ngP@ssw0rd";

    private static final String FIRST_NAME = "Ivan";

    private static final String LAST_NAME = "Ivanov";

    private static final String KEYCLOAK_USER_ID = "0b7a5f2c-19d4-4a8e-9c3b-77e1a0d4f5b6";

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    @Mock
    private PersonClient personClient;

    @Mock
    private KeycloakClient keycloakClient;

    @Mock
    private TokenService tokenService;

    @Mock
    private AuthMetrics metrics;

    @Spy
    private ObservationRegistry observationRegistry = ObservationRegistry.create();

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("given every step succeeds, when registering, then it walks them in order and stamps user_uid")
    void registersInOrder() {
        // given
        when(personClient.createPerson(EMAIL, FIRST_NAME, LAST_NAME)).thenReturn(Mono.just(USER_UID));
        when(keycloakClient.createAccount(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString()))
                .thenReturn(Mono.just(KEYCLOAK_USER_ID));
        when(keycloakClient.resetUserPassword(KEYCLOAK_USER_ID, PASSWORD)).thenReturn(Mono.empty());
        when(keycloakClient.assignPlatformRole(KEYCLOAK_USER_ID)).thenReturn(Mono.empty());
        when(tokenService.issueTokens(EMAIL, PASSWORD)).thenReturn(Mono.just(new TokenResponse().userUid(USER_UID)));

        // when / then
        StepVerifier.create(userService.register(request()))
                .assertNext(tokens -> assertThat(tokens.getUserUid()).isEqualTo(USER_UID))
                .verifyComplete();

        // then
        InOrder inOrder = Mockito.inOrder(personClient, keycloakClient, tokenService);
        inOrder.verify(personClient).createPerson(EMAIL, FIRST_NAME, LAST_NAME);
        inOrder.verify(keycloakClient).createAccount(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString());
        inOrder.verify(keycloakClient).resetUserPassword(KEYCLOAK_USER_ID, PASSWORD);
        inOrder.verify(keycloakClient).assignPlatformRole(KEYCLOAK_USER_ID);
        inOrder.verify(tokenService).issueTokens(EMAIL, PASSWORD);
        verify(keycloakClient, never()).rollbackAccountWithError(anyString(), any());
        verify(metrics).registrationStarted();
        verify(metrics).registrationSucceeded();
        verify(metrics, never()).registrationFailed();
    }

    @Test
    @DisplayName("given person-service refuses, when registering, then Keycloak is never touched")
    void stopsBeforeKeycloakWhenTheDomainUserIsRefused() {
        // given
        when(personClient.createPerson(EMAIL, FIRST_NAME, LAST_NAME))
                .thenReturn(Mono.error(new ApiException(ErrorCode.USER_ALREADY_EXISTS)));

        // when / then
        StepVerifier.create(userService.register(request()))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.USER_ALREADY_EXISTS))
                .verify();

        // then
        verifyNoInteractions(keycloakClient);
    }

    @Test
    @DisplayName("given the password cannot be set, when registering, then the account is deleted again")
    void compensatesWhenThePasswordFails() {
        // given
        when(personClient.createPerson(EMAIL, FIRST_NAME, LAST_NAME)).thenReturn(Mono.just(USER_UID));
        when(keycloakClient.createAccount(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString()))
                .thenReturn(Mono.just(KEYCLOAK_USER_ID));
        when(keycloakClient.resetUserPassword(KEYCLOAK_USER_ID, PASSWORD))
                .thenReturn(Mono.error(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE)));
        when(keycloakClient.rollbackAccountWithError(eq(KEYCLOAK_USER_ID), any()))
                .thenAnswer(call -> Mono.error(call.getArgument(1, Throwable.class)));

        // when / then
        StepVerifier.create(userService.register(request()))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_INCONSISTENT))
                .verify();

        // then
        verify(keycloakClient).rollbackAccountWithError(eq(KEYCLOAK_USER_ID), any());
        verify(tokenService, never()).issueTokens(anyString(), anyString());
        verify(metrics).registrationFailed();
        verify(metrics, never()).registrationSucceeded();
    }

    @Test
    @DisplayName("UT-REG-004: given Keycloak is unreachable after the domain user exists, "
            + "then it answers 503 and records the partial failure")
    void reportsInconsistencyWhenTheAccountFails() {
        // given
        when(personClient.createPerson(EMAIL, FIRST_NAME, LAST_NAME)).thenReturn(Mono.just(USER_UID));
        when(keycloakClient.createAccount(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString()))
                .thenReturn(Mono.error(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE)));

        // when / then
        StepVerifier.create(userService.register(request()))
                .expectErrorSatisfies(error -> {
                    ErrorCode code = ((ApiException) error).getErrorCode();
                    assertThat(code).isEqualTo(ErrorCode.REGISTRATION_INCONSISTENT);
                    assertThat(code.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                })
                .verify();

        // then
        verify(keycloakClient, never()).rollbackAccountWithError(anyString(), any());
        verify(tokenService, never()).issueTokens(anyString(), anyString());
        verify(metrics).registrationFailed();
        verify(metrics, never()).registrationSucceeded();
    }

    @Test
    @DisplayName("given only the final login fails, when registering, then the finished account is kept")
    void doesNotCompensateWhenTheFinalLoginFails() {
        // given
        when(personClient.createPerson(EMAIL, FIRST_NAME, LAST_NAME)).thenReturn(Mono.just(USER_UID));
        when(keycloakClient.createAccount(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString()))
                .thenReturn(Mono.just(KEYCLOAK_USER_ID));
        when(keycloakClient.resetUserPassword(KEYCLOAK_USER_ID, PASSWORD)).thenReturn(Mono.empty());
        when(keycloakClient.assignPlatformRole(KEYCLOAK_USER_ID)).thenReturn(Mono.empty());
        when(tokenService.issueTokens(EMAIL, PASSWORD))
                .thenReturn(Mono.error(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE)));

        // when / then
        StepVerifier.create(userService.register(request()))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .verify();

        // then
        verify(keycloakClient, never()).rollbackAccountWithError(anyString(), any());
    }

    private static RegistrationRequest request() {
        return new RegistrationRequest()
                .email(EMAIL)
                .password(PASSWORD)
                .confirmPassword(PASSWORD)
                .firstName(FIRST_NAME)
                .lastName(LAST_NAME);
    }

    private static final OffsetDateTime REGISTERED_AT =
            OffsetDateTime.of(2026, 3, 14, 9, 30, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("UT-ME-001: given a valid bearer token, when asking who am I, then every field of the answer is filled")
    void answersWhoTheCallerIs() {
        // given
        when(keycloakClient.findRegisteredAt(KEYCLOAK_USER_ID)).thenReturn(Mono.just(REGISTERED_AT));

        // when / then
        StepVerifier.create(userService.currentUser(accessToken()))
                .assertNext(user -> {
                    assertThat(user.getUserUid()).isEqualTo(USER_UID);
                    assertThat(user.getKeycloakUserId()).isEqualTo(KEYCLOAK_USER_ID);
                    assertThat(user.getEmail()).isEqualTo(EMAIL);
                    assertThat(user.getFirstName()).isEqualTo("Ivan");
                    assertThat(user.getLastName()).isEqualTo("Ivanov");
                    assertThat(user.getEmailVerified()).isTrue();
                    assertThat(user.getRoles()).containsExactly("USER");
                    assertThat(user.getRegisteredAt()).isEqualTo(REGISTERED_AT);
                })
                .verifyComplete();

        // then
        verify(tokenService, never()).issueTokens(anyString(), anyString());
    }

    @Test
    @DisplayName("given a token without user_uid, when asking who am I, then it answers 500 rather than a half-filled user")
    void refusesATokenWithoutTheDomainIdentifier() {
        // given
        when(keycloakClient.findRegisteredAt(KEYCLOAK_USER_ID)).thenReturn(Mono.just(REGISTERED_AT));

        // when / then
        StepVerifier.create(userService.currentUser(tokenWithoutUserUid()))
                .expectErrorSatisfies(error -> {
                    ErrorCode code = ((ApiException) error).getErrorCode();
                    assertThat(code).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(code.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                })
                .verify();
    }

    @Test
    @DisplayName("given valid credentials, when logging in, then TokenService answers and nothing else is touched")
    void loginGoesThroughTokenService() {
        // given
        TokenResponse tokens = new TokenResponse().userUid(USER_UID);
        when(tokenService.login(EMAIL, PASSWORD)).thenReturn(Mono.just(tokens));

        // when / then
        StepVerifier.create(userService.login(EMAIL, PASSWORD))
                .expectNext(tokens)
                .verifyComplete();

        // then
        verifyNoInteractions(personClient, keycloakClient);
    }

    @Test
    @DisplayName("given a refresh token, when refreshing, then TokenService answers with the new pair")
    void refreshGoesThroughTokenService() {
        // given
        TokenResponse tokens = new TokenResponse().userUid(USER_UID);
        when(tokenService.refresh("refresh-token")).thenReturn(Mono.just(tokens));

        // when / then
        StepVerifier.create(userService.refresh("refresh-token"))
                .expectNext(tokens)
                .verifyComplete();

        verifyNoInteractions(personClient, keycloakClient);
    }
}
