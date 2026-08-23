package com.dezxxx.individuals.service;

import com.dezxxx.individuals.api.model.RegistrationRequest;
import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.admin.KeycloakAdminGateway;
import com.dezxxx.individuals.gateway.keycloak.oidc.KeycloakOidcGateway;
import com.dezxxx.individuals.gateway.person.PersonServiceGateway;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Orchestrates registration across two systems that share no transaction.
 *
 * <p>The order is fixed: person-service first, because it issues the identity
 * and a refusal from it leaves nothing to undo; Keycloak second, in two calls;
 * an ordinary login last, by which point both systems already agree.
 *
 * <p>Only {@code userUid} travels through this class. Keycloak's own id lives
 * for the length of one chain, purely so the account can be removed again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final PersonServiceGateway personServiceGateway;

    private final KeycloakAdminGateway keycloakAdminGateway;

    private final KeycloakOidcGateway keycloakOidcGateway;

    /** The request is already valid - {@code @Valid} ran on the controller. */
    public Mono<TokenResponse> register(RegistrationRequest request) {
        return personServiceGateway
                .createPerson(request.getEmail(), request.getFirstName(), request.getLastName())
                .flatMap(userUid -> createAccount(request, userUid)
                        .then(login(request, userUid)))
                .doOnSuccess(tokens -> log.info("Registered {}", tokens.getUserUid()));
    }

    /**
     * The two Keycloak writes, treated as one unit. Both run after
     * person-service has committed, so any failure inside is an inconsistent
     * registration - the original cause is carried up and turned into our code
     * in exactly one place.
     */
    private Mono<Void> createAccount(RegistrationRequest request, UUID userUid) {
        return keycloakAdminGateway
                .createUser(request.getEmail(), request.getFirstName(), request.getLastName(), userUid.toString())
                .flatMap(keycloakUserId -> keycloakAdminGateway
                        .setPassword(keycloakUserId, request.getPassword())
                        .onErrorResume(cause -> undo(keycloakUserId, cause)))
                .then()
                .onErrorMap(cause -> inconsistent(userUid, cause));
    }

    /**
     * Removes an account that never got a password, then re-raises the original
     * failure. A failed cleanup is logged and swallowed - replacing the real
     * cause with it would hide why registration broke.
     */
    private Mono<Void> undo(String keycloakUserId, Throwable cause) {
        log.warn("Password was not set for Keycloak account {}, removing it", keycloakUserId, cause);
        return keycloakAdminGateway.deleteUser(keycloakUserId)
                .onErrorResume(undoFailed -> {
                    log.error("Could not remove the incomplete Keycloak account {}", keycloakUserId, undoFailed);
                    return Mono.empty();
                })
                .then(Mono.error(cause));
    }

    /**
     * Records a half-finished registration and names it in the answer.
     *
     * <p>person-service has committed a domain user Keycloak knows nothing
     * about, and its contract offers no delete, so the split cannot be undone
     * from here - module 1's accepted limit. The caller cannot recover either:
     * a second attempt is now answered <b>409 (Conflict)</b> by person-service.
     * Hence a dedicated code rather than a generic 500, and a log line carrying
     * the user_uid a human needs to clean it up.
     */
    private ApiException inconsistent(UUID userUid, Throwable cause) {
        log.error("Inconsistent registration: domain user {} exists in person-service "
                + "but its Keycloak account was not completed", userUid, cause);
        return new ApiException(ErrorCode.REGISTRATION_INCONSISTENT);
    }

    /** {@code userUid} is in no token answer, so it is stamped on from step 1. */
    private Mono<TokenResponse> login(RegistrationRequest request, UUID userUid) {
        return keycloakOidcGateway.login(request.getEmail(), request.getPassword())
                .map(tokens -> tokens.userUid(userUid));
    }
}
