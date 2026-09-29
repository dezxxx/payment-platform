package com.dezxxx.individuals.gateway.person;

import com.dezxxx.individuals.config.PersonServiceProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.util.GatewayErrors;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.person.client.api.PersonsApi;
import com.dezxxx.person.client.model.PersonRegistrationRequest;
import com.dezxxx.person.client.model.PersonRegistrationResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

// The only class that talks to person-service. Used once: at registration,
// to create the person and get user_uid. The HTTP call itself is the generated
// PersonsApi; this class turns its answer and its errors into ours.
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonClient {

    // name of the dependency in "unreachable" logs
    private static final String PERSON_SERVICE = "person-service";

    private final PersonsApi personsApi;
    private final PersonServiceProperties properties;
    private final AuthMetrics metrics;

    // creates the person, returns user_uid. No password: that belongs to Keycloak.
    public Mono<UUID> createPerson(String email, String firstName, String lastName) {
        PersonRegistrationRequest body = new PersonRegistrationRequest(email, firstName, lastName);
        return metrics.timePersonService(call(() -> personsApi.registerPerson(Mono.just(body))))
                .map(PersonClient::userUidOf)
                .onErrorMap(WebClientResponseException.class, PersonClient::translate)
                .transform(GatewayErrors.transportFailures(PERSON_SERVICE, properties.baseUrl()));
    }

    // user_uid from the answer; an answer without it is person-service's bug -> 500
    private static UUID userUidOf(ResponseEntity<PersonRegistrationResponse> response) {
        PersonRegistrationResponse body = response.getBody();
        if (body == null || body.getUserUid() == null) {
            log.error("person-service answered {} without a userUid", response.getStatusCode());
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return body.getUserUid();
    }

    // person-service's status -> our code. Its body goes to the log only:
    // it belongs to another service and may name columns or constraints.
    private static ApiException translate(WebClientResponseException ex) {
        ErrorCode code = classify(ex.getStatusCode());
        log.warn("person-service answered {}: {} -> {}", ex.getStatusCode().value(), ex.getResponseBodyAsString(), code);
        return new ApiException(code);
    }

    private static ErrorCode classify(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.CONFLICT)) {
            return ErrorCode.USER_ALREADY_EXISTS;
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.is5xxServerError()) {
            return ErrorCode.DEPENDENCY_UNAVAILABLE;
        }
        // a 400 means their validation and ours disagree - our bug, not the caller's
        return ErrorCode.INTERNAL_ERROR;
    }

    // the generated methods declare "throws Exception" (generator option), but the
    // proxy never throws - errors arrive inside the Mono. This satisfies the compiler once.
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
