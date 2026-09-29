package com.dezxxx.individuals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

// individuals-api owns no data: it orchestrates person-service (the person)
// and Keycloak (the account and its tokens)
@SpringBootApplication
public class IndividualsApiApplication {

    public static void main(String[] args) {
        // On the reactive stack the current span lives in the Reactor context,
        // not in a ThreadLocal, so Tracer.currentSpan() would return null and
        // every error body would carry no trace id. This restores the bridge
        // between the two, and is also what puts traceId into the JSON logs.
        Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(IndividualsApiApplication.class, args);
    }
}
