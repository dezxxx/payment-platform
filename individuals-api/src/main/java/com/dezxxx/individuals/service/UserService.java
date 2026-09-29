package com.dezxxx.individuals.service;

import com.dezxxx.individuals.api.model.CurrentUserResponse;
import com.dezxxx.individuals.api.model.RegistrationRequest;
import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.client.KeycloakClient;
import com.dezxxx.individuals.gateway.person.PersonClient;
import com.dezxxx.individuals.logging.RequestLog;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.util.KeycloakClaims;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

// The user: registration and /me.
// Registration spans two systems with no shared transaction, so the order is
// fixed: person-service first (it issues user_uid), then Keycloak, then login.
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    // claim names in a Keycloak access token
    private static final String EMAIL = "email";
    private static final String GIVEN_NAME = "given_name";
    private static final String FAMILY_NAME = "family_name";
    private static final String EMAIL_VERIFIED = "email_verified";

    // roles Keycloak gives every account for itself - not shown in /me
    private static final Set<String> KEYCLOAK_OWN_ROLES = Set.of("offline_access", "uma_authorization");
    private static final String DEFAULT_ROLES_PREFIX = "default-roles-";

    private final PersonClient personClient;
    private final KeycloakClient keycloakClient;
    private final AuthMetrics metrics;

    // --- registration ---

    // the request is already valid: @Valid ran on the controller
    public Mono<TokenResponse> register(RegistrationRequest request) {
        return personClient
                .createPerson(request.getEmail(), request.getFirstName(), request.getLastName())
                // defer: without it login() would be called while the chain is
                // built - before the account exists
                .flatMap(userUid -> createAccount(request, userUid)
                        .then(Mono.defer(() -> login(request, userUid))))
                .doFirst(metrics::registrationStarted)
                .doOnError(cause -> metrics.registrationFailed())
                .doOnSuccess(tokens -> {
                    metrics.registrationSucceeded();
                    RequestLog.userUid(tokens.getUserUid());
                    log.info("Registered {}", tokens.getUserUid());
                });
    }

    // the Keycloak part: account -> password -> role; if password or role fails,
    // the half-made account is rolled back
    private Mono<Void> createAccount(RegistrationRequest request, UUID userUid) {
        return keycloakClient
                .createAccount(request.getEmail(), request.getFirstName(), request.getLastName(), userUid.toString())
                .flatMap(keycloakUserId -> keycloakClient
                        .resetUserPassword(keycloakUserId, request.getPassword())
                        .then(Mono.defer(() -> keycloakClient.assignPlatformRole(keycloakUserId)))
                        .onErrorResume(cause -> keycloakClient.rollbackAccountWithError(keycloakUserId, cause)))
                .then()
                .onErrorMap(cause -> inconsistent(userUid, cause));
    }

    // person-service already holds the person and cannot delete it - module 1's
    // accepted limit. A retry would get 409, so the answer names the problem
    // and the log carries the user_uid a human needs to clean it up.
    private ApiException inconsistent(UUID userUid, Throwable cause) {
        log.error("Inconsistent registration: domain user {} exists in person-service "
                + "but its Keycloak account was not completed", userUid, cause);
        return new ApiException(ErrorCode.REGISTRATION_INCONSISTENT);
    }

    // user_uid is not in Keycloak's token answer; here we already have it
    private Mono<TokenResponse> login(RegistrationRequest request, UUID userUid) {
        return keycloakClient.login(request.getEmail(), request.getPassword())
                .map(tokens -> tokens.userUid(userUid));
    }

    // --- /me ---

    // 7 of 8 fields come from the token the security chain already verified;
    // registeredAt is in no claim, so it costs one Keycloak call
    public Mono<CurrentUserResponse> currentUser(Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return keycloakClient.findRegisteredAt(keycloakUserId)
                .map(registeredAt -> toCurrentUser(jwt, keycloakUserId, registeredAt));
    }

    private CurrentUserResponse toCurrentUser(Jwt jwt, String keycloakUserId, OffsetDateTime registeredAt) {
        UUID userUid = KeycloakClaims.requireUserUid(jwt);
        RequestLog.userUid(userUid);
        return new CurrentUserResponse()
                .userUid(userUid)
                .keycloakUserId(keycloakUserId)
                .email(jwt.getClaimAsString(EMAIL))
                .firstName(jwt.getClaimAsString(GIVEN_NAME))
                .lastName(jwt.getClaimAsString(FAMILY_NAME))
                .emailVerified(jwt.getClaimAsBoolean(EMAIL_VERIFIED))
                .roles(platformRoles(jwt))
                .registeredAt(registeredAt);
    }

    // token roles minus Keycloak's own - the client asks about business roles
    private static List<String> platformRoles(Jwt jwt) {
        return KeycloakClaims.realmRoles(jwt).stream()
                .filter(role -> !KEYCLOAK_OWN_ROLES.contains(role))
                .filter(role -> !role.startsWith(DEFAULT_ROLES_PREFIX))
                .toList();
    }
}
