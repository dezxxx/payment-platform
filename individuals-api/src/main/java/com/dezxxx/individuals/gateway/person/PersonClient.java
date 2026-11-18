package com.dezxxx.individuals.gateway.person;

import com.dezxxx.individuals.api.model.RegistrationRequestDto;
import com.dezxxx.individuals.api.model.RegistrationRequestAddressDto;
import com.dezxxx.individuals.api.model.RegistrationRequestIndividualDto;
import com.dezxxx.individuals.config.PersonServiceProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.util.GatewayErrors;
import com.dezxxx.person.client.api.IndividualsApi;
import com.dezxxx.person.client.model.CreateIndividualRequestDto;
import com.dezxxx.person.client.model.CreateIndividualRequestUserAddressDto;
import com.dezxxx.person.client.model.CreateIndividualRequestUserDto;
import com.dezxxx.person.client.model.IndividualResponseDto;
import com.dezxxx.person.client.model.IndividualResponseUserDto;
import com.dezxxx.person.client.model.ProblemResponseDto;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
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

    private final IndividualsApi individualsApi;
    private final PersonServiceProperties properties;
    private final AuthMetrics metrics;
    private final CircuitBreakerRegistry breakers;

    public Mono<UUID> createPerson(RegistrationRequestDto request) {
        return guarded(() -> individualsApi.createIndividual(Mono.just(toCreateIndividual(request))),
                PersonClient::userUidOf);
    }

    public Mono<PersonProfile> findProfile(UUID userUid) {
        return guarded(() -> individualsApi.getIndividualById(userUid), PersonClient::profileOf);
    }

    public Mono<Void> compensateRegistration(UUID userUid) {
        return guarded(() -> individualsApi.compensateRegistration(userUid), Function.identity()).then();
    }

    private <T, R> Mono<R> guarded(Supplier<Mono<T>> call, Function<T, R> result) {
        return metrics.timePersonService(Mono.defer(call))
                .transformDeferred(CircuitBreakerOperator.of(breakers.circuitBreaker(PERSON_SERVICE)))
                .map(result)
                .onErrorMap(WebClientResponseException.class, PersonClient::translate)
                .transform(GatewayErrors.transportFailures(PERSON_SERVICE, properties.baseUrl()));
    }

    private static CreateIndividualRequestDto toCreateIndividual(RegistrationRequestDto request) {
        RegistrationRequestAddressDto address = request.getAddress();
        RegistrationRequestIndividualDto individual = request.getIndividual();
        return new CreateIndividualRequestDto()
                .passportNumber(individual.getPassportNumber())
                .phoneNumber(individual.getPhoneNumber())
                .user(new CreateIndividualRequestUserDto()
                        .email(request.getEmail())
                        .firstName(request.getFirstName())
                        .lastName(request.getLastName())
                        .address(new CreateIndividualRequestUserAddressDto()
                                .countryAlpha3(address.getCountryAlpha3())
                                .countryAlpha2(address.getCountryAlpha2())
                                .city(address.getCity())
                                .state(address.getState())
                                .zipCode(address.getZipCode())
                                .addressLine(address.getAddressLine())));
    }

    private static UUID userUidOf(ResponseEntity<IndividualResponseDto> response) {
        IndividualResponseDto body = response.getBody();
        if (body == null || body.getId() == null) {
            log.error("person-service answered {} without an individual id", response.getStatusCode());
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return body.getId();
    }

    private static PersonProfile profileOf(ResponseEntity<IndividualResponseDto> response) {
        IndividualResponseDto body = response.getBody();
        if (body == null || body.getUser() == null) {
            log.error("person-service answered {} without the individual's user", response.getStatusCode());
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        IndividualResponseUserDto user = body.getUser();
        return new PersonProfile(user.getEmail(), user.getFirstName(), user.getLastName());
    }

    // its body goes to the log only: it may name columns or constraints
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
