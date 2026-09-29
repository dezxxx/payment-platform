package com.dezxxx.individuals.util;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

// Shared by KeycloakClient and PersonClient: "the other side did not answer at all"
@Slf4j
public final class GatewayErrors {

    private GatewayErrors() {
        throw new UnsupportedOperationException("Utility class");
    }

    // connection refused, DNS failure, timeout -> 503: not the caller's fault,
    // a retry may work. An ApiException is already ours and passes through.
    public static <T> Function<Mono<T>, Mono<T>> transportFailures(String dependency, String baseUrl) {
        return mono -> mono.onErrorMap(
                ex -> !(ex instanceof ApiException),
                ex -> {
                    log.error("{} is unreachable at {}", dependency, baseUrl, ex);
                    return new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                });
    }
}
