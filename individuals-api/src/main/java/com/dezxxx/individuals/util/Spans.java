package com.dezxxx.individuals.util;

import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Mono;

// not @Observed: it does not handle Mono
public final class Spans {

    private Spans() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static <T> Function<Mono<T>, Mono<T>> named(String name, ObservationRegistry registry) {
        return mono -> mono.name(name).tap(Micrometer.observation(registry));
    }
}
