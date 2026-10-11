package com.dezxxx.person.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.person.api.model.UpdateUserRequestAddressDto;
import com.dezxxx.person.api.model.UpdateUserRequestDto;
import com.dezxxx.person.api.model.UserResponseDto;
import com.dezxxx.person.integration.support.IntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

@DisplayName("Users API against a real PostgreSQL")
class UserApiIT extends IntegrationTest {

    @Test
    @DisplayName("given a full request, when creating, then 201 and user, address and individual are stored together")
    void createsUser() {
        // given
        String email = freshEmail("create");

        // when
        UserResponseDto user = createUser(email);

        // then
        assertThat(user.getId()).isNotNull();
        assertThat(user.getFilled()).isTrue();
        assertThat(user.getIndividual().getStatus()).isEqualTo("NEW");
        assertThat(user.getAddress().getCountryAlpha3()).isEqualTo("UKR");
        assertThat(Duration.between(user.getCreatedAt(), OffsetDateTime.now()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(rowsOf("person.users", "id", user.getId())).isEqualTo(1);
        assertThat(rowsOf("person.addresses", "id", user.getAddress().getId())).isEqualTo(1);
        assertThat(rowsOf("person.individuals", "user_id", user.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("given an email already taken, when creating it again in another letter case, then 409 EMAIL_ALREADY_EXISTS")
    void refusesDuplicateEmail() {
        // given
        String email = freshEmail("duplicate");
        createUser(email);

        // when / then
        client.post().uri("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createRequest(email.toUpperCase()))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("EMAIL_ALREADY_EXISTS")
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.instance").isEqualTo("/api/v1/users")
                .jsonPath("$.traceId").exists();
    }

    @Test
    @DisplayName("given a created user, when reading by id, then the whole aggregate comes back")
    void readsById() {
        // given
        UserResponseDto created = createUser(freshEmail("by-id"));

        // when
        UserResponseDto read = client.get().uri("/api/v1/users/{id}", created.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(read.getId()).isEqualTo(created.getId());
        assertThat(read.getAddress().getId()).isEqualTo(created.getAddress().getId());
        assertThat(read.getIndividual().getId()).isEqualTo(created.getIndividual().getId());
    }

    @Test
    @DisplayName("given a created user, when reading by email in another letter case, then it is found")
    void readsByEmail() {
        // given
        String email = freshEmail("by-email");
        UserResponseDto created = createUser(email);

        // when
        UserResponseDto read = client.get().uri("/api/v1/users/by-email?email={email}", email.toUpperCase())
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(read.getId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("given a user, when one PATCH changes the user and its address, then both change and the rest is kept")
    void patchesNestedEntities() {
        // given
        UserResponseDto created = createUser(freshEmail("patch"));
        UpdateUserRequestDto patch = new UpdateUserRequestDto()
                .lastName("Petrov-Senior")
                .address(new UpdateUserRequestAddressDto().city("Lviv").countryAlpha3("POL"));

        // when
        UserResponseDto updated = client.patch().uri("/api/v1/users/{id}", created.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(patch)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(updated.getLastName()).isEqualTo("Petrov-Senior");
        assertThat(updated.getFirstName()).isEqualTo("Ivan");
        assertThat(updated.getAddress().getCity()).isEqualTo("Lviv");
        assertThat(updated.getAddress().getCountryAlpha3()).isEqualTo("POL");
        assertThat(updated.getAddress().getZipCode()).isEqualTo("01001");
        assertThat(updated.getUpdatedAt()).isAfter(created.getUpdatedAt());
    }

    @Test
    @DisplayName("given a user, when deleting, then 204 and no row of the aggregate is left")
    void deletesWholeAggregate() {
        // given
        UserResponseDto created = createUser(freshEmail("delete"));

        // when
        client.delete().uri("/api/v1/users/{id}", created.getId())
                .exchange()
                .expectStatus().isNoContent();

        // then
        assertThat(rowsOf("person.users", "id", created.getId())).isZero();
        assertThat(rowsOf("person.addresses", "id", created.getAddress().getId())).isZero();
        assertThat(rowsOf("person.individuals", "id", created.getIndividual().getId())).isZero();
        client.get().uri("/api/v1/users/{id}", created.getId())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("given an unknown id, when reading, then 404 USER_NOT_FOUND in RFC 9457 form")
    void unknownId() {
        // when / then
        client.get().uri("/api/v1/users/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://example.org/problems/user-not-found")
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.error").isEqualTo("USER_NOT_FOUND")
                .jsonPath("$.details").isEmpty();
    }

    @Test
    @DisplayName("given fields that break the contract, when creating, then 400 VALIDATION_ERROR names each field in English")
    void invalidBody() {
        // when / then
        client.post().uri("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept-Language", "ru")
                .body("{\"email\":\"not-an-email\",\"firstName\":\"\",\"address\":{\"city\":\"Kyiv\",\"addressLine\":\"1\"},\"individual\":{}}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[?(@ == 'email: must be a well-formed email address')]").exists()
                .jsonPath("$.details[?(@ =~ /firstName: .*/)]").exists()
                .jsonPath("$.details[?(@ =~ /lastName: .*/)]").exists();
    }

    @Test
    @DisplayName("given an id that is not a UUID, when reading, then 400 VALIDATION_ERROR")
    void idNotUuid() {
        // when / then
        client.get().uri("/api/v1/users/not-a-uuid")
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

        // when / then
        client.post().uri("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createRequest(email).address(createRequest(email).getAddress().countryAlpha3("XXX")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("COUNTRY_NOT_FOUND")
                .jsonPath("$.detail").isEqualTo("Unknown country code: XXX");

        // then
        assertThat(rowsOf("person.users", "email", email)).isZero();
    }
}
