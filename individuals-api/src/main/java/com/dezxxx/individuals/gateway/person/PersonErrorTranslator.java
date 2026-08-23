package com.dezxxx.individuals.gateway.person;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Turns a failed person-service answer into one of our {@link ErrorCode}s.
 *
 * <p>Shorter than its Keycloak counterpart: person-service is ours and already
 * speaks the platform's error model, so only the status has to be re-read.
 *
 * <p>The failure arrives as an exception rather than a body - the generated
 * client is a proxy over {@code WebClient}, which throws on any 4xx or 5xx.
 */
@Slf4j
public final class PersonErrorTranslator {

    private PersonErrorTranslator() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Throwable translate(WebClientResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        ErrorCode code = classify(status);
        // Logged, never returned: the body belongs to another service's
        // contract and may name columns, constraints or identifiers.
        log.warn("person-service answered {}: {} -> {}",
                status.value(), ex.getResponseBodyAsString(), code);
        return new ApiException(code);
    }

    private static ErrorCode classify(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.CONFLICT)) {
            return ErrorCode.USER_ALREADY_EXISTS;
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.is5xxServerError()) {
            return ErrorCode.DEPENDENCY_UNAVAILABLE;
        }
        // A 400 means their validation and ours disagree, which is our bug.
        // The caller is never blamed for a request they did not build.
        return ErrorCode.INTERNAL_ERROR;
    }
}
