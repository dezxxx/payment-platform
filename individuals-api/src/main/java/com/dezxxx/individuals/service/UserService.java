package com.dezxxx.individuals.service;

import com.dezxxx.individuals.api.model.CurrentUserResponseDto;
import com.dezxxx.individuals.api.model.RegistrationRequestDto;
import com.dezxxx.individuals.api.model.TokenResponseDto;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.client.KeycloakClient;
import com.dezxxx.individuals.gateway.person.PersonClient;
import com.dezxxx.individuals.logging.RequestLog;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.util.KeycloakClaims;
import com.dezxxx.individuals.util.Spans;
import io.micrometer.observation.ObservationRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

// no shared transaction: person-service first (it issues user_uid), then Keycloak, then login
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String EMAIL = "email";
    private static final String GIVEN_NAME = "given_name";
    private static final String FAMILY_NAME = "family_name";
    private static final String EMAIL_VERIFIED = "email_verified";

    private static final Set<String> KEYCLOAK_OWN_ROLES = Set.of("offline_access", "uma_authorization");
    private static final String DEFAULT_ROLES_PREFIX = "default-roles-";

    private final PersonClient personClient;
    private final KeycloakClient keycloakClient;
    private final TokenService tokenService;
    private final AuthMetrics metrics;
    private final ObservationRegistry observationRegistry;

    public Mono<TokenResponseDto> register(RegistrationRequestDto request) {
        return personClient
                .createPerson(request)
                .transform(span("registration.createPerson"))
                // defer: otherwise login() runs while the chain is built, before the account exists
                .flatMap(userUid -> createAccount(request, userUid)
                        .then(Mono.defer(() -> login(request))))
                .doFirst(metrics::registrationStarted)
                .doOnError(cause -> metrics.registrationFailed())
                .doOnSuccess(tokens -> {
                    metrics.registrationSucceeded();
                    RequestLog.userUid(tokens.getUserUid());
                    log.info("Registered {}", tokens.getUserUid());
                })
                .transform(span("registration"));
    }

    private Mono<Void> createAccount(RegistrationRequestDto request, UUID userUid) {
        return keycloakClient
                .createAccount(request.getEmail(), request.getFirstName(), request.getLastName(), userUid.toString())
                .transform(span("registration.createAccount"))
                .flatMap(keycloakUserId -> keycloakClient
                        .resetUserPassword(keycloakUserId, request.getPassword())
                        .transform(span("registration.resetPassword"))
                        .then(Mono.defer(() -> keycloakClient.assignPlatformRole(keycloakUserId)
                                .transform(span("registration.assignRole"))))
                        .onErrorResume(cause -> keycloakClient.rollbackAccountWithError(keycloakUserId, cause)
                                .transform(span("registration.rollbackAccount"))))
                .then()
                .onErrorResume(cause -> compensate(userUid, cause));
    }

    private Mono<Void> compensate(UUID userUid, Throwable cause) {
        return personClient.compensateRegistration(userUid)
                .transform(span("registration.compensateRegistration"))
                .doOnSuccess(done -> log.warn("Registration of {} rolled back in person-service", userUid))
                .onErrorMap(deleteFailure -> inconsistent(userUid, cause, deleteFailure))
                .then(Mono.error(cause));
    }

    private ApiException inconsistent(UUID userUid, Throwable cause, Throwable deleteFailure) {
        log.error("Inconsistent registration: individual {} exists in person-service, its Keycloak account "
                + "failed and deleting the user failed too", userUid, deleteFailure);
        return new ApiException(ErrorCode.REGISTRATION_INCONSISTENT, ErrorCode.REGISTRATION_INCONSISTENT.getDefaultMessage(), cause);
    }

    private Mono<TokenResponseDto> login(RegistrationRequestDto request) {
        return tokenService.issueTokens(request.getEmail(), request.getPassword())
                .transform(span("registration.login"));
    }

    public Mono<TokenResponseDto> login(String email, String password) {
        return tokenService.login(email, password);
    }

    public Mono<TokenResponseDto> refresh(String refreshToken) {
        return tokenService.refresh(refreshToken);
    }

    public Mono<CurrentUserResponseDto> currentUser(Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return keycloakClient.findRegisteredAt(keycloakUserId)
                .transform(span("me.findRegisteredAt"))
                .map(registeredAt -> toCurrentUser(jwt, keycloakUserId, registeredAt))
                .transform(span("me"));
    }

    private CurrentUserResponseDto toCurrentUser(Jwt jwt, String keycloakUserId, OffsetDateTime registeredAt) {
        UUID userUid = KeycloakClaims.requireUserUid(jwt);
        RequestLog.userUid(userUid);
        return new CurrentUserResponseDto()
                .userUid(userUid)
                .keycloakUserId(keycloakUserId)
                .email(jwt.getClaimAsString(EMAIL))
                .firstName(jwt.getClaimAsString(GIVEN_NAME))
                .lastName(jwt.getClaimAsString(FAMILY_NAME))
                .emailVerified(jwt.getClaimAsBoolean(EMAIL_VERIFIED))
                .roles(platformRoles(jwt))
                .registeredAt(registeredAt);
    }

    private <T> Function<Mono<T>, Mono<T>> span(String name) {
        return Spans.named(name, observationRegistry);
    }

    private static List<String> platformRoles(Jwt jwt) {
        return KeycloakClaims.realmRoles(jwt).stream()
                .filter(role -> !KEYCLOAK_OWN_ROLES.contains(role))
                .filter(role -> !role.startsWith(DEFAULT_ROLES_PREFIX))
                .toList();
    }
}
