package com.dezxxx.individuals.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dezxxx.individuals.api.model.RegistrationRequest;
import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.admin.KeycloakAdminGateway;
import com.dezxxx.individuals.gateway.keycloak.oidc.KeycloakOidcGateway;
import com.dezxxx.individuals.gateway.person.PersonServiceGateway;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.service.RegistrationService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The registration scenario, with its dependencies replaced by mocks.
 *
 * <p>What is worth testing here is not the happy path but the failures: two
 * systems are written to with no transaction across them, so every step has a
 * different answer to "what is left behind if this one breaks". Those answers
 * cannot be checked by hand - reproducing them for real would mean breaking
 * Keycloak halfway through a call.
 *
 * <p>Given / when / then throughout. On the reactive stack the middle and the
 * last step are one expression: nothing runs until {@code StepVerifier}
 * subscribes, so the call and its assertions cannot be written apart.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegistrationService")
class RegistrationServiceTest {

    private static final String EMAIL = "user@dezxxx.com";

    private static final String PASSWORD = "Str0ngP@ssw0rd";

    private static final String FIRST_NAME = "Ivan";

    private static final String LAST_NAME = "Ivanov";

    private static final String KEYCLOAK_USER_ID = "0b7a5f2c-19d4-4a8e-9c3b-77e1a0d4f5b6";

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    @Mock
    private PersonServiceGateway personServiceGateway;

    @Mock
    private KeycloakAdminGateway keycloakAdminGateway;

    @Mock
    private KeycloakOidcGateway keycloakOidcGateway;

    /**
     * Counting is a side effect, so a mock is enough: the meters themselves
     * belong to Micrometer and need no test of ours. What is worth asserting is
     * that the right counter is reached on the right outcome.
     */
    @Mock
    private AuthMetrics metrics;

    @InjectMocks
    private RegistrationService registrationService;

    /**
     * The order is the design, so the order is asserted: a Keycloak account
     * must never exist before person-service has issued the identity it
     * carries.
     */
    @Test
    @DisplayName("given every step succeeds, when registering, then it walks them in order and stamps user_uid")
    void registersInOrder() {
        // given
        when(personServiceGateway.createPerson(EMAIL, FIRST_NAME, LAST_NAME)).thenReturn(Mono.just(USER_UID));
        when(keycloakAdminGateway.createUser(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString()))
                .thenReturn(Mono.just(KEYCLOAK_USER_ID));
        when(keycloakAdminGateway.setPassword(KEYCLOAK_USER_ID, PASSWORD)).thenReturn(Mono.empty());
        when(keycloakAdminGateway.assignPlatformRole(KEYCLOAK_USER_ID)).thenReturn(Mono.empty());
        when(keycloakOidcGateway.login(EMAIL, PASSWORD)).thenReturn(Mono.just(new TokenResponse()));

        // when / then - user_uid is in no token Keycloak issues, it is carried
        // over from step 1, the only place that knows it
        StepVerifier.create(registrationService.register(request()))
                .assertNext(tokens -> assertThat(tokens.getUserUid()).isEqualTo(USER_UID))
                .verifyComplete();

        // then
        InOrder inOrder = Mockito.inOrder(personServiceGateway, keycloakAdminGateway, keycloakOidcGateway);
        inOrder.verify(personServiceGateway).createPerson(EMAIL, FIRST_NAME, LAST_NAME);
        inOrder.verify(keycloakAdminGateway).createUser(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString());
        inOrder.verify(keycloakAdminGateway).setPassword(KEYCLOAK_USER_ID, PASSWORD);
        // After the password, not before: an account that cannot be logged into
        // has no use for a role, and this order keeps the compensation below
        // covering every write Keycloak has seen.
        inOrder.verify(keycloakAdminGateway).assignPlatformRole(KEYCLOAK_USER_ID);
        inOrder.verify(keycloakOidcGateway).login(EMAIL, PASSWORD);
        verify(keycloakAdminGateway, never()).deleteUser(anyString());
        verify(metrics).registrationStarted();
        verify(metrics).registrationSucceeded();
        verify(metrics, never()).registrationFailed();
    }

    /**
     * The reason person-service goes first: its refusal leaves nothing behind
     * anywhere, so there is nothing to compensate and the caller keeps the
     * original answer - a duplicate email must stay <b>409 (Conflict)</b>
     * rather than be flattened into our inconsistency code.
     */
    @Test
    @DisplayName("given person-service refuses, when registering, then Keycloak is never touched")
    void stopsBeforeKeycloakWhenTheDomainUserIsRefused() {
        // given
        when(personServiceGateway.createPerson(EMAIL, FIRST_NAME, LAST_NAME))
                .thenReturn(Mono.error(new ApiException(ErrorCode.USER_ALREADY_EXISTS)));

        // when / then
        StepVerifier.create(registrationService.register(request()))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.USER_ALREADY_EXISTS))
                .verify();

        // then
        verifyNoInteractions(keycloakAdminGateway, keycloakOidcGateway);
    }

    /**
     * The compensation. An account without a password is unusable and would
     * hold the address forever, so it is removed - and the caller is told the
     * registration is inconsistent, because person-service has already
     * committed a domain user that nothing will clean up.
     */
    @Test
    @DisplayName("given the password cannot be set, when registering, then the account is deleted again")
    void compensatesWhenThePasswordFails() {
        // given
        when(personServiceGateway.createPerson(EMAIL, FIRST_NAME, LAST_NAME)).thenReturn(Mono.just(USER_UID));
        when(keycloakAdminGateway.createUser(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString()))
                .thenReturn(Mono.just(KEYCLOAK_USER_ID));
        when(keycloakAdminGateway.setPassword(KEYCLOAK_USER_ID, PASSWORD))
                .thenReturn(Mono.error(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE)));
        when(keycloakAdminGateway.deleteUser(KEYCLOAK_USER_ID)).thenReturn(Mono.empty());

        // when / then
        StepVerifier.create(registrationService.register(request()))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_INCONSISTENT))
                .verify();

        // then
        verify(keycloakAdminGateway).deleteUser(KEYCLOAK_USER_ID);
        verifyNoInteractions(keycloakOidcGateway);
        // A registration that was rolled back is still a failed registration -
        // the counter must not be reserved for errors we did not compensate.
        verify(metrics).registrationFailed();
        verify(metrics, never()).registrationSucceeded();
    }

    /**
     * A failed cleanup must not replace the original failure. It is logged and
     * swallowed - the caller still gets the inconsistency code, and the
     * half-made account becomes a problem for a human rather than a different
     * error message.
     */
    @Test
    @DisplayName("given the cleanup also fails, when registering, then the inconsistency is still what is reported")
    void survivesAFailedCompensation() {
        // given
        when(personServiceGateway.createPerson(EMAIL, FIRST_NAME, LAST_NAME)).thenReturn(Mono.just(USER_UID));
        when(keycloakAdminGateway.createUser(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString()))
                .thenReturn(Mono.just(KEYCLOAK_USER_ID));
        when(keycloakAdminGateway.setPassword(KEYCLOAK_USER_ID, PASSWORD))
                .thenReturn(Mono.error(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE)));
        when(keycloakAdminGateway.deleteUser(KEYCLOAK_USER_ID))
                .thenReturn(Mono.error(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE)));

        // when / then
        StepVerifier.create(registrationService.register(request()))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_INCONSISTENT))
                .verify();
    }

    /**
     * UT-REG-004. Nothing to undo here: the account was never created, so only
     * person-service holds a record. Same outcome as a failed password, and no
     * delete call - removing an id we never received would be a bug of its own.
     */
    @Test
    @DisplayName("UT-REG-004: given Keycloak is unreachable after the domain user exists, "
            + "then it answers 503 and records the partial failure")
    void reportsInconsistencyWhenTheAccountFails() {
        // given
        when(personServiceGateway.createPerson(EMAIL, FIRST_NAME, LAST_NAME)).thenReturn(Mono.just(USER_UID));
        when(keycloakAdminGateway.createUser(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString()))
                .thenReturn(Mono.error(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE)));

        // when / then
        StepVerifier.create(registrationService.register(request()))
                .expectErrorSatisfies(error -> {
                    ErrorCode code = ((ApiException) error).getErrorCode();
                    assertThat(code).isEqualTo(ErrorCode.REGISTRATION_INCONSISTENT);
                    // The status is asserted, not only the code: the handout
                    // fixes 502 or 503 for this case, and a code carrying the
                    // wrong status would otherwise pass unnoticed.
                    assertThat(code.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                })
                .verify();

        // then - the partial failure is recorded, which is the other half of
        // what the handout asks for here
        verify(keycloakAdminGateway, never()).deleteUser(anyString());
        verifyNoInteractions(keycloakOidcGateway);
        verify(metrics).registrationFailed();
        verify(metrics, never()).registrationSucceeded();
    }

    /**
     * The last step is the only one whose failure leaves both systems correct:
     * the user exists and the password works, only the tokens were not handed
     * out. So the account must survive and the original cause must reach the
     * caller unchanged - a second login attempt simply succeeds.
     */
    @Test
    @DisplayName("given only the final login fails, when registering, then the finished account is kept")
    void doesNotCompensateWhenTheFinalLoginFails() {
        // given
        when(personServiceGateway.createPerson(EMAIL, FIRST_NAME, LAST_NAME)).thenReturn(Mono.just(USER_UID));
        when(keycloakAdminGateway.createUser(EMAIL, FIRST_NAME, LAST_NAME, USER_UID.toString()))
                .thenReturn(Mono.just(KEYCLOAK_USER_ID));
        when(keycloakAdminGateway.setPassword(KEYCLOAK_USER_ID, PASSWORD)).thenReturn(Mono.empty());
        when(keycloakAdminGateway.assignPlatformRole(KEYCLOAK_USER_ID)).thenReturn(Mono.empty());
        when(keycloakOidcGateway.login(EMAIL, PASSWORD))
                .thenReturn(Mono.error(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE)));

        // when / then
        StepVerifier.create(registrationService.register(request()))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .verify();

        // then
        verify(keycloakAdminGateway, never()).deleteUser(anyString());
    }

    private static RegistrationRequest request() {
        return new RegistrationRequest()
                .email(EMAIL)
                .password(PASSWORD)
                .confirmPassword(PASSWORD)
                .firstName(FIRST_NAME)
                .lastName(LAST_NAME);
    }
}
