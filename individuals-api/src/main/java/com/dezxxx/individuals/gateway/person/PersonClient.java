package com.dezxxx.individuals.gateway.person;

import com.dezxxx.individuals.api.model.RegistrationRequest;
import com.dezxxx.individuals.api.model.RegistrationRequestAddress;
import com.dezxxx.individuals.api.model.RegistrationRequestIndividual;
import com.dezxxx.individuals.config.PersonServiceProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.util.GatewayErrors;
import com.dezxxx.person.client.api.UsersApi;
import com.dezxxx.person.client.model.CreateUserRequestAddressDto;
import com.dezxxx.person.client.model.CreateUserRequestDto;
import com.dezxxx.person.client.model.CreateUserRequestIndividualDto;
import com.dezxxx.person.client.model.ProblemResponseDto;
import com.dezxxx.person.client.model.UserResponseDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class PersonClient {

    private static final String PERSON_SERVICE = "person-service";

    private static final String UNKNOWN_COUNTRY = "COUNTRY_NOT_FOUND";

    private final UsersApi usersApi;
    private final PersonServiceProperties properties;
    private final AuthMetrics metrics;

    public Mono<UUID> createPerson(RegistrationRequest request) {
        return metrics.timePersonService(Mono.defer(() -> usersApi.createUser(Mono.just(toCreateUser(request)))))
                .map(PersonClient::userUidOf)
                .onErrorMap(WebClientResponseException.class, PersonClient::translate)
                .transform(GatewayErrors.transportFailures(PERSON_SERVICE, properties.baseUrl()));
    }

    // the compensation step of registration: the domain user goes when its Keycloak account failed
    public Mono<Void> deletePerson(UUID userUid) {
        return metrics.timePersonService(Mono.defer(() -> usersApi.deleteUser(userUid)))
                .then()
                .onErrorMap(WebClientResponseException.class, PersonClient::translate)
                .transform(GatewayErrors.transportFailures(PERSON_SERVICE, properties.baseUrl()));
    }

    private static CreateUserRequestDto toCreateUser(RegistrationRequest request) {
        RegistrationRequestAddress address = request.getAddress();
        RegistrationRequestIndividual individual = request.getIndividual();
        return new CreateUserRequestDto()
                .email(request.getEmail())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .address(new CreateUserRequestAddressDto()
                        .countryAlpha3(address.getCountryAlpha3())
                        .countryAlpha2(address.getCountryAlpha2())
                        .city(address.getCity())
                        .state(address.getState())
                        .zipCode(address.getZipCode())
                        .addressLine(address.getAddressLine()))
                .individual(new CreateUserRequestIndividualDto()
                        .passportNumber(individual.getPassportNumber())
                        .phoneNumber(individual.getPhoneNumber()));
    }

    private static UUID userUidOf(ResponseEntity<UserResponseDto> response) {
        UserResponseDto body = response.getBody();
        if (body == null || body.getId() == null) {
            log.error("person-service answered {} without a user id", response.getStatusCode());
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return body.getId();
    }

    // person-service's status -> our code. Its body goes to the log only:
    // it belongs to another service and may name columns or constraints.
    private static ApiException translate(WebClientResponseException ex) {
        if (isUnknownCountry(ex)) {
            ProblemResponseDto problem = ex.getResponseBodyAs(ProblemResponseDto.class);
            return new ApiException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                    List.of("address: " + problem.getDetail()), ex);
        }
        ErrorCode code = classify(ex.getStatusCode());
        log.warn("person-service answered {}: {} -> {}", ex.getStatusCode().value(), ex.getResponseBodyAsString(), code);
        return new ApiException(code);
    }

    // only person-service knows the country list, so an unknown code is the caller's mistake
    private static boolean isUnknownCountry(WebClientResponseException ex) {
        if (!ex.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
            return false;
        }
        ProblemResponseDto problem = ex.getResponseBodyAs(ProblemResponseDto.class);
        return problem != null && UNKNOWN_COUNTRY.equals(problem.getError());
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
}
