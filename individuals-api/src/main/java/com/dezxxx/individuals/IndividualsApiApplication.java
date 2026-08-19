package com.dezxxx.individuals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
        SpringApplication.run(IndividualsApiApplication.class, args);
    }
}
