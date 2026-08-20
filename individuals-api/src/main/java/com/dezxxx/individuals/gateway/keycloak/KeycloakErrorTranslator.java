package com.dezxxx.individuals.gateway.keycloak;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/**
 * Turns a failed Keycloak answer into one of our {@link ErrorCode}s.
 *
 * <p>Both Keycloak gateways need exactly this, so it lives on its own: final
 * class, private constructor, static methods, no state.
 *
 * <p>The translation is the whole point of a gateway. Keycloak's status codes
 * do not mean what our contract means:
 *
 * <ul>
 *   <li>a wrong password is <b>400</b> there and <b>401</b> here;</li>
 *   <li>a wrong client secret is also 401 there, but it is <b>our</b>
 *       misconfiguration, not the caller's fault, so it becomes a 500;</li>
 *   <li>a 4xx we did not foresee means we sent a malformed request, which is
 *       a bug on our side and again a 500 - never a 400 blamed on the caller.</li>
 * </ul>
 *
 * <p>Keycloak's own wording is logged but never returned. It leaks internals
 * ("Account is not fully set up") and would let an attacker tell a registered
 * address from an unknown one.
 */
@Slf4j
public final class KeycloakErrorTranslator {

    private static final String INVALID_GRANT = "invalid_grant";

    /**
     * Both mean "this service failed to authenticate itself". The OAuth 2 spec
     * defines {@code invalid_client}; Keycloak 26 answers the token endpoint
     * with {@code unauthorized_client}. Verified against a running 26.7.2, so
     * both are matched rather than trusting either one.
     */
    private static final Set<String> CLIENT_AUTH_FAILURES = Set.of("invalid_client", "unauthorized_client");

    private KeycloakErrorTranslator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads the failure body and produces the exception to fail the chain with.
     *
     * <p>Shaped for {@code WebClient}: {@code onStatus} expects a function that
     * returns the error as a Mono, so the body can be read without blocking.
     */
    public static Mono<Throwable> translate(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(KeycloakErrorResponse.class)
                // A failure may carry no body at all, or one we cannot parse -
                // neither is a reason to lose the status code.
                .defaultIfEmpty(KeycloakErrorResponse.EMPTY)
                .onErrorReturn(KeycloakErrorResponse.EMPTY)
                .map(body -> toApiException(status, body));
    }

    private static ApiException toApiException(HttpStatusCode status, KeycloakErrorResponse body) {
        ErrorCode code = classify(status, body);
        log.warn("Keycloak answered {} ({}): {} -> {}",
                status.value(), body.error(), body.describe(), code);
        return new ApiException(code);
    }

    private static ErrorCode classify(HttpStatusCode status, KeycloakErrorResponse body) {
        String error = body.error();
        // Guarded on purpose: a failure may carry no body, and Set.of(..)
        // throws on contains(null) rather than answering false.
        if (error != null && CLIENT_AUTH_FAILURES.contains(error)) {
            // Our own client id or secret is wrong. The caller did nothing
            // wrong and must not be told 401 for it.
            return ErrorCode.INTERNAL_ERROR;
        }
        if (INVALID_GRANT.equals(error)) {
            // Wrong password, expired or reused refresh token, account not
            // fully set up - all of them are "these credentials do not work".
            return ErrorCode.INVALID_CREDENTIALS;
        }
        if (status.isSameCodeAs(HttpStatus.CONFLICT)) {
            return ErrorCode.USER_ALREADY_EXISTS;
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.is5xxServerError()) {
            return ErrorCode.DEPENDENCY_UNAVAILABLE;
        }
        return ErrorCode.INTERNAL_ERROR;
    }
}
