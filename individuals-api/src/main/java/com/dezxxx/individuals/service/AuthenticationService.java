package com.dezxxx.individuals.service;

import com.dezxxx.individuals.api.model.CurrentUserResponse;
import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.admin.KeycloakAdminGateway;
import com.dezxxx.individuals.gateway.keycloak.oidc.KeycloakOidcGateway;
import com.dezxxx.individuals.util.KeycloakClaims;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Everything after registration: logging in, staying logged in, and answering
 * "who am I".
 *
 * <p>Nothing here writes. person-service is never called - the account already
 * exists and only Keycloak has anything to say about it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private static final String EMAIL = "email";

    private static final String GIVEN_NAME = "given_name";

    private static final String FAMILY_NAME = "family_name";

    private static final String EMAIL_VERIFIED = "email_verified";

    private final KeycloakOidcGateway keycloakOidcGateway;

    private final KeycloakAdminGateway keycloakAdminGateway;

    /**
     * Decodes the access token we just received back from Keycloak.
     *
     * <p>The same bean the resource server uses for inbound tokens: it checks
     * the signature against the realm JWKS, which is cached, so this costs no
     * network call after the first one.
     */
    private final ReactiveJwtDecoder jwtDecoder;

    public Mono<TokenResponse> login(String email, String password) {
        return keycloakOidcGateway.login(email, password)
                .flatMap(this::withUserUid)
                .doOnSuccess(tokens -> log.info("Logged in {}", tokens.getUserUid()));
    }

    public Mono<TokenResponse> refresh(String refreshToken) {
        return keycloakOidcGateway.refresh(refreshToken)
                .flatMap(this::withUserUid);
    }

    /**
     * Answers {@code /me}.
     *
     * <p>Seven of the eight fields come from the token, which the security
     * filter chain has already verified - reading them costs nothing. The
     * eighth, {@code registeredAt}, is in no claim, so it is worth one admin
     * read. This service owns no database, so there is nowhere else to keep it.
     */
    public Mono<CurrentUserResponse> currentUser(Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return keycloakAdminGateway.findRegisteredAt(keycloakUserId)
                .map(registeredAt -> toCurrentUser(jwt, keycloakUserId, registeredAt));
    }

    private CurrentUserResponse toCurrentUser(Jwt jwt, String keycloakUserId, OffsetDateTime registeredAt) {
        return new CurrentUserResponse()
                .userUid(userUidOf(jwt))
                .keycloakUserId(keycloakUserId)
                .email(jwt.getClaimAsString(EMAIL))
                .firstName(jwt.getClaimAsString(GIVEN_NAME))
                .lastName(jwt.getClaimAsString(FAMILY_NAME))
                .emailVerified(jwt.getClaimAsBoolean(EMAIL_VERIFIED))
                .roles(KeycloakClaims.realmRoles(jwt))
                .registeredAt(registeredAt);
    }

    /**
     * Stamps the domain identifier onto a token pair.
     *
     * <p>{@code TokenResponse} requires {@code userUid}, and the OIDC token
     * endpoint knows nothing about it - the value reaches us only as a claim
     * the realm's mapper wrote into the access token. Registration takes a
     * shorter route: it already holds the identifier person-service issued a
     * moment earlier and never decodes anything.
     */
    private Mono<TokenResponse> withUserUid(TokenResponse tokens) {
        return jwtDecoder.decode(tokens.getAccessToken())
                .map(jwt -> tokens.userUid(userUidOf(jwt)))
                // Our own token failed our own decoder: a realm key rotation
                // mid-flight, or clocks that disagree. Either way the caller
                // did nothing wrong.
                .onErrorMap(ex -> !(ex instanceof ApiException), ex -> {
                    log.error("Could not read the access token Keycloak just issued", ex);
                    return new ApiException(ErrorCode.INTERNAL_ERROR);
                });
    }

    /**
     * The claim is missing only when the realm's {@code user_uid} mapper is
     * gone or the account was created outside our registration flow. Both are
     * configuration faults on our side, so the caller is told
     * <b>500 (Internal Server Error)</b> rather than handed a response with a
     * required field left empty.
     */
    private UUID userUidOf(Jwt jwt) {
        return KeycloakClaims.userUid(jwt).orElseThrow(() -> {
            log.error("Access token of {} carries no usable user_uid claim", jwt.getSubject());
            return new ApiException(ErrorCode.INTERNAL_ERROR);
        });
    }
}
