package com.dezxxx.person.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.person.api.model.CreateIndividualRequestDto;
import com.dezxxx.person.api.model.IndividualResponseDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserDto;
import com.dezxxx.person.integration.support.IntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

@DisplayName("IndividualRestControllerV1 against a real PostgreSQL")
class IndividualRestControllerV1IT extends IntegrationTest {

    @Test
    @DisplayName("given a full request, when creating, then 201 and individual, user and address are stored together")
    void createsIndividual() {
        // given
        String email = freshEmail("create");

        // when
        IndividualResponseDto individual = createIndividual(email);

        // then
        assertThat(individual.getId()).isNotNull();
        assertThat(individual.getStatus()).isEqualTo(IndividualResponseDto.StatusEnum.NEW);
        assertThat(Duration.between(individual.getCreatedAt(), OffsetDateTime.now()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(individual.getUser().getFilled()).isTrue();
        assertThat(individual.getUser().getAddress().getCountryAlpha3()).isEqualTo("UKR");
        assertThat(Duration.between(individual.getUser().getCreatedAt(), OffsetDateTime.now()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(rowsOf("person.individuals", "id", individual.getId())).isEqualTo(1);
        assertThat(rowsOf("person.individuals", "user_id", individual.getUser().getId())).isEqualTo(1);
        assertThat(rowsOf("person.users", "id", individual.getUser().getId())).isEqualTo(1);
        assertThat(rowsOf("person.addresses", "id", individual.getUser().getAddress().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("given an email already taken, when creating it again in another letter case, then 409 EMAIL_ALREADY_EXISTS")
    void refusesDuplicateEmail() {
        // given
        String email = freshEmail("duplicate");
        createIndividual(email);

        // when / then
        client.post().uri("/api/v1/individuals")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createRequest(email.toUpperCase()))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("EMAIL_ALREADY_EXISTS")
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.instance").isEqualTo("/api/v1/individuals")
                .jsonPath("$.traceId").exists();
    }

    @Test
    @DisplayName("given a created individual, when reading by id, then the whole aggregate comes back")
    void readsById() {
        // given
        IndividualResponseDto created = createIndividual(freshEmail("by-id"));

        // when
        IndividualResponseDto read = client.get().uri("/api/v1/individuals/{id}", created.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(IndividualResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(read.getId()).isEqualTo(created.getId());
        assertThat(read.getUser().getId()).isEqualTo(created.getUser().getId());
        assertThat(read.getUser().getAddress().getId()).isEqualTo(created.getUser().getAddress().getId());
    }

    @Test
    @DisplayName("given a created individual, when reading by email in another letter case, then it is found")
    void readsByEmail() {
        // given
        String email = freshEmail("by-email");
        IndividualResponseDto created = createIndividual(email);

        // when
        IndividualResponseDto read = client.get().uri("/api/v1/individuals/by-email?email={email}", email.toUpperCase())
                .exchange()
                .expectStatus().isOk()
                .expectBody(IndividualResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(read.getId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("given an individual, when one PATCH changes the individual, its user and the address, then all change and the rest is kept")
    void patchesNestedEntities() {
        // given
        IndividualResponseDto created = createIndividual(freshEmail("patch"));
        UpdateIndividualRequestDto patch = new UpdateIndividualRequestDto()
                .phoneNumber("+380441234568")
                .user(new UpdateIndividualRequestUserDto()
                        .lastName("Petrov-Senior")
                        .address(new UpdateIndividualRequestUserAddressDto().city("Lviv").countryAlpha3("POL")));

        // when
        IndividualResponseDto updated = client.patch().uri("/api/v1/individuals/{id}", created.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(patch)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IndividualResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(updated.getPhoneNumber()).isEqualTo("+380441234568");
        assertThat(updated.getPassportNumber()).isEqualTo("123456789");
        assertThat(updated.getUser().getLastName()).isEqualTo("Petrov-Senior");
        assertThat(updated.getUser().getFirstName()).isEqualTo("Ivan");
        assertThat(updated.getUser().getAddress().getCity()).isEqualTo("Lviv");
        assertThat(updated.getUser().getAddress().getCountryAlpha3()).isEqualTo("POL");
        assertThat(updated.getUser().getAddress().getZipCode()).isEqualTo("01001");
        assertThat(updated.getUser().getUpdatedAt()).isAfter(created.getUser().getUpdatedAt());
    }

    @Test
    @DisplayName("given an individual, when deleting, then 204, its rows stay ARCHIVED and it is found no more")
    void archivesIndividual() {
        // given
        String email = freshEmail("archive");
        IndividualResponseDto created = createIndividual(email);

        // when
        client.delete().uri("/api/v1/individuals/{id}", created.getId())
                .exchange()
                .expectStatus().isNoContent();

        // then
        assertThat(jdbc.queryForObject("select status from person.individuals where id = ?", String.class, created.getId()))
                .isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("select archived_at is not null from person.individuals where id = ?", Boolean.class, created.getId()))
                .isTrue();
        assertThat(rowsOf("person.users", "id", created.getUser().getId())).isEqualTo(1);
        client.get().uri("/api/v1/individuals/{id}", created.getId()).exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/individuals/by-email?email={email}", email).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/v1/individuals/{id}", created.getId()).exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("given an individual, when compensating its registration, then 204 and no row of the aggregate is left")
    void compensationDeletesWholeAggregate() {
        // given
        IndividualResponseDto created = createIndividual(freshEmail("compensate"));

        // when
        client.delete().uri("/api/v1/individuals/compensate-registration/{id}", created.getId())
                .exchange()
                .expectStatus().isNoContent();

        // then
        assertThat(rowsOf("person.individuals", "id", created.getId())).isZero();
        assertThat(rowsOf("person.users", "id", created.getUser().getId())).isZero();
        assertThat(rowsOf("person.addresses", "id", created.getUser().getAddress().getId())).isZero();
        client.get().uri("/api/v1/individuals/{id}", created.getId())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("given an unknown id, when reading, then 404 INDIVIDUAL_NOT_FOUND in RFC 9457 form")
    void unknownId() {
        // when / then
        client.get().uri("/api/v1/individuals/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://example.org/problems/individual-not-found")
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.error").isEqualTo("INDIVIDUAL_NOT_FOUND")
                .jsonPath("$.details").isEmpty();
    }

    @Test
    @DisplayName("given fields that break the contract or are missing, when creating, then 400 VALIDATION_ERROR names each field in English")
    void invalidBody() {
        // when / then
        client.post().uri("/api/v1/individuals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept-Language", "ru")
                .body("{\"user\":{\"email\":\"not-an-email\",\"firstName\":\"\",\"address\":{\"city\":\"Kyiv\",\"addressLine\":\"1\"}}}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[?(@ == 'user.email: must be a well-formed email address')]").exists()
                .jsonPath("$.details[?(@ =~ /user.firstName: .*/)]").exists()
                .jsonPath("$.details[?(@ =~ /user.lastName: .*/)]").exists()
                .jsonPath("$.details[?(@ =~ /passportNumber: .*/)]").exists()
                .jsonPath("$.details[?(@ =~ /phoneNumber: .*/)]").exists()
                .jsonPath("$.details[?(@ =~ /user.address.countryAlpha3: .*/)]").exists();
    }

    @Test
    @DisplayName("given an empty email, when creating, then 400 VALIDATION_ERROR and no individual, user or address is stored")
    void refusesEmptyEmail() {
        // given
        CreateIndividualRequestDto request = createRequest(freshEmail("empty"));
        request.getUser().email("");
        long individuals = countOf("person.individuals");
        long users = countOf("person.users");
        long addresses = countOf("person.addresses");

        // when / then
        client.post().uri("/api/v1/individuals")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[?(@ =~ /user.email: .*/)]").exists();

        // then
        assertThat(countOf("person.individuals")).isEqualTo(individuals);
        assertThat(countOf("person.users")).isEqualTo(users);
        assertThat(countOf("person.addresses")).isEqualTo(addresses);
    }

    @Test
    @DisplayName("given a method the path does not support, when calling, then 405 in RFC 9457 form with the Allow header")
    void methodNotAllowedNamesTheAllowedMethods() {
        // when / then
        client.put().uri("/api/v1/individuals")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value("Allow", allow -> assertThat(allow).contains("POST"))
                .expectBody()
                .jsonPath("$.error").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("given an id that is not a UUID, when reading, then 400 VALIDATION_ERROR")
    void idNotUuid() {
        // when / then
        client.get().uri("/api/v1/individuals/not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").isEqualTo("id: invalid value");
    }

    @Test
    @DisplayName("given an unknown country code, when creating, then 400 COUNTRY_NOT_FOUND and nothing is stored")
    void unknownCountry() {
        // given
        String email = freshEmail("country");
        CreateIndividualRequestDto request = createRequest(email);
        request.getUser().getAddress().countryAlpha3("XXX");

        // when / then
        client.post().uri("/api/v1/individuals")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("COUNTRY_NOT_FOUND")
                .jsonPath("$.detail").isEqualTo("Unknown country code: XXX");

        // then
        assertThat(rowsOf("person.users", "email", email)).isZero();
    }
}
