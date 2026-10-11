package com.dezxxx.individuals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class IndividualsApiApplication {

    public static void main(String[] args) {
        // the span lives in the Reactor context: without this, error bodies and logs lose the traceId
        Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(IndividualsApiApplication.class, args);
    }
}
