package com.dezxxx.individuals.util;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
public final class GatewayErrors {

    private GatewayErrors() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static <T> Function<Mono<T>, Mono<T>> transportFailures(String dependency, String baseUrl) {
        return mono -> mono.onErrorMap(
                ex -> !(ex instanceof ApiException),
                ex -> {
                    log.error("{} is unreachable at {}", dependency, baseUrl, ex);
                    return new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                });
    }
}
