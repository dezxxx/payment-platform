package com.dezxxx.individuals.util;

import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Mono;

// Names a step of a scenario as a span in the trace: "registration.createPerson".
// The span opens on subscribe and closes when the Mono ends, so its time is real,
// and the HTTP calls made inside it become its children.
// Not @WithSpan (needs a second tracing library) and not @Observed (does not handle Mono).
public final class Spans {

    private Spans() {
        throw new UnsupportedOperationException("Utility class");
    }

    // usage: mono.transform(Spans.named("registration.createAccount", observationRegistry))
    public static <T> Function<Mono<T>, Mono<T>> named(String name, ObservationRegistry registry) {
        return mono -> mono.name(name).tap(Micrometer.observation(registry));
    }
}
