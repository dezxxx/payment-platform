package com.dezxxx.individuals.gateway.person;

import com.dezxxx.individuals.config.PersonServiceProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.GatewayErrors;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.person.client.api.PersonsApi;
import com.dezxxx.person.client.model.PersonRegistrationRequest;
import com.dezxxx.person.client.model.PersonRegistrationResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * The only class that talks to person-service.
 *
 * <p>The generated {@code PersonsApi} could be injected into the service layer
 * directly, but it exposes three things that must not travel upwards: a
 * {@code ResponseEntity}, a checked {@code throws Exception}, and another
 * service's status codes. What leaves here is a {@code Mono<UUID>}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonServiceGateway {

    private static final String PERSON_SERVICE = "person-service";

    private final PersonsApi personsApi;

    private final PersonServiceProperties properties;

    private final AuthMetrics metrics;

    /**
     * Creates the domain user and returns the identifier the whole platform
     * uses for them. No password is sent - credentials belong to Keycloak.
     */
    public Mono<UUID> createPerson(String email, String firstName, String lastName) {
        PersonRegistrationRequest body = new PersonRegistrationRequest(email, firstName, lastName);
        return metrics.timePersonService(call(() -> personsApi.registerPerson(Mono.just(body))))
                .map(PersonServiceGateway::userUidOf)
                .onErrorMap(WebClientResponseException.class, PersonErrorTranslator::translate)
                .transform(GatewayErrors.transportFailures(PERSON_SERVICE, properties.baseUrl()));
    }

    private static UUID userUidOf(ResponseEntity<PersonRegistrationResponse> response) {
        PersonRegistrationResponse body = response.getBody();
        if (body == null || body.getUserUid() == null) {
            log.error("person-service answered {} without a userUid", response.getStatusCode());
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return body.getUserUid();
    }

    /**
     * Runs a generated call inside the chain.
     *
     * <p>The generated methods declare {@code throws Exception} - an artefact
     * of the generator's {@code unhandledException} option, meant for the
     * server side. The proxy never throws it; every failure travels inside the
     * {@code Mono}. This satisfies the compiler once instead of a try/catch per
     * method.
     */
    private static <T> Mono<T> call(PersonApiCall<T> apiCall) {
        return Mono.defer(() -> {
            try {
                return apiCall.execute();
            } catch (Exception ex) {
                return Mono.error(ex);
            }
        });
    }

    @FunctionalInterface
    private interface PersonApiCall<T> {
        Mono<T> execute() throws Exception;
    }
}
