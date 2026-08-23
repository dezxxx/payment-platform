package com.dezxxx.individuals.gateway;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * What every gateway does the same way, whichever system it talks to.
 *
 * <p>Lives in the parent package because neither {@code keycloak} nor
 * {@code person} owns it - shared code sits at the level that covers everyone
 * who uses it.
 */
@Slf4j
public final class GatewayErrors {

    private GatewayErrors() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Turns "the dependency never answered at all" - connection refused, DNS
     * failure, timeout - into <b>503 (Service Unavailable)</b>. None of those
     * are the caller's mistake, and the same request may work once the
     * dependency is back.
     *
     * <p>An {@link ApiException} passes through: it is already ours, and
     * re-wrapping would hide the real code.
     */
    public static <T> Function<Mono<T>, Mono<T>> transportFailures(String dependency, String baseUrl) {
        return mono -> mono.onErrorMap(
                ex -> !(ex instanceof ApiException),
                ex -> {
                    log.error("{} is unreachable at {}", dependency, baseUrl, ex);
                    return new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                });
    }
}
