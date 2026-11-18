package com.dezxxx.individuals.config;

import java.util.function.Predicate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

// a 4xx is an answer of a working service, so it does not move the breaker towards open
public class DependencyFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable ex) {
        return !(ex instanceof WebClientResponseException response && response.getStatusCode().is4xxClientError());
    }
}
