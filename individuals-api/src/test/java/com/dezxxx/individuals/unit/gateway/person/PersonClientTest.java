package com.dezxxx.individuals.unit.gateway.person;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.api.model.RegistrationRequestDto;
import com.dezxxx.individuals.api.model.RegistrationRequestAddressDto;
import com.dezxxx.individuals.api.model.RegistrationRequestIndividualDto;
import com.dezxxx.individuals.config.PersonServiceProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.person.PersonClient;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.person.client.api.IndividualsApi;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("PersonClient")
class PersonClientTest {

    private static final PersonServiceProperties PROPERTIES =
            new PersonServiceProperties("http://person-service", Duration.ofSeconds(5));

    private static final UUID USER_UID = UUID.fromString("7fd6212d-0f71-42a0-bf6e-7dbce6cf04fb");

    @Test
    @DisplayName("given person-service creates the individual, when creating, then its id is the user_uid")
    void createsPerson() {
        // given
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        PersonClient client = clientAnswering(sent, json(HttpStatus.CREATED, "{\"id\":\"" + USER_UID + "\"}"));

        // when / then
        StepVerifier.create(client.createPerson(request()))
                .expectNext(USER_UID)
                .verifyComplete();

        // then
        assertThat(sent.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(sent.get().url().getPath()).isEqualTo("/api/v1/individuals");
    }

    @Test
    @DisplayName("given an answer without an id, when creating, then INTERNAL_ERROR - person-service broke its contract")
    void answerWithoutId() {
        // given
        PersonClient client = clientAnswering(new AtomicReference<>(), json(HttpStatus.CREATED, "{}"));

        // when / then
        expectError(client.createPerson(request()), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("given the email is taken in person-service, when creating, then USER_ALREADY_EXISTS")
    void emailTaken() {
        // given
        PersonClient client = clientAnswering(new AtomicReference<>(), problem(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "taken"));

        // when / then
        expectError(client.createPerson(request()), ErrorCode.USER_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("given an unknown country code, when creating, then VALIDATION_ERROR names the address - the caller's mistake")
    void unknownCountry() {
        // given
        PersonClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.BAD_REQUEST, "COUNTRY_NOT_FOUND", "Unknown country code: XXX"));

        // when / then
        StepVerifier.create(client.createPerson(request()))
                .expectErrorSatisfies(error -> {
                    ApiException ex = (ApiException) error;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getDetails()).isEqualTo(List.of("address: Unknown country code: XXX"));
                })
                .verify();
    }

    @Test
    @DisplayName("given any other 400, when creating, then INTERNAL_ERROR - the two contracts disagree")
    void otherBadRequest() {
        // given
        PersonClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed"));

        // when / then
        expectError(client.createPerson(request()), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("given person-service fails, when creating, then DEPENDENCY_UNAVAILABLE")
    void serverError() {
        // given
        PersonClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error"));

        // when / then
        expectError(client.createPerson(request()), ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("given person-service deletes the individual, when compensating, then DELETE goes to the compensation path and completes")
    void compensatesRegistration() {
        // given
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        PersonClient client = clientAnswering(sent, ClientResponse.create(HttpStatus.NO_CONTENT).build());

        // when / then
        StepVerifier.create(client.compensateRegistration(USER_UID)).verifyComplete();

        // then
        assertThat(sent.get().method()).isEqualTo(HttpMethod.DELETE);
        assertThat(sent.get().url().getPath()).isEqualTo("/api/v1/individuals/compensate-registration/" + USER_UID);
    }

    @Test
    @DisplayName("given the individual is already gone, when compensating, then NOT_FOUND")
    void deleteNotFound() {
        // given
        PersonClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.NOT_FOUND, "INDIVIDUAL_NOT_FOUND", "Individual not found"));

        // when / then
        expectError(client.compensateRegistration(USER_UID), ErrorCode.NOT_FOUND);
    }

    private static void expectError(Mono<?> call, ErrorCode expected) {
        StepVerifier.create(call)
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo(expected))
                .verify();
    }

    private static PersonClient clientAnswering(AtomicReference<ClientRequest> sent, ClientResponse response) {
        WebClient webClient = WebClient.builder()
                .baseUrl(PROPERTIES.baseUrl())
                .exchangeFunction(request -> {
                    sent.set(request);
                    return Mono.just(response);
                })
                .build();
        IndividualsApi individualsApi = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient))
                .build()
                .createClient(IndividualsApi.class);
        return new PersonClient(individualsApi, PROPERTIES, new AuthMetrics(new SimpleMeterRegistry()));
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static ClientResponse problem(HttpStatus status, String error, String detail) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .body("{\"status\":" + status.value() + ",\"error\":\"" + error + "\",\"detail\":\"" + detail + "\"}")
                .build();
    }

    private static RegistrationRequestDto request() {
        return new RegistrationRequestDto()
                .email("ivan.petrov@example.org")
                .password("Str0ngP@ssw0rd")
                .confirmPassword("Str0ngP@ssw0rd")
                .firstName("Ivan")
                .lastName("Petrov")
                .address(new RegistrationRequestAddressDto().countryAlpha3("UKR").city("Kyiv").addressLine("1 Example St."))
                .individual(new RegistrationRequestIndividualDto().passportNumber("123456789").phoneNumber("+380441234567"));
    }
}
