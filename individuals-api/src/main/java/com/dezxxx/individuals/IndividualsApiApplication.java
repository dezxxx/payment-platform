package com.dezxxx.individuals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

/**
 * Entry point of the external entry layer of the payment platform.
 *
 * <p>This application owns no domain data. It orchestrates person-service,
 * the source of truth for the domain user, and Keycloak, the source of truth
 * for the account and its tokens.
 */
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
