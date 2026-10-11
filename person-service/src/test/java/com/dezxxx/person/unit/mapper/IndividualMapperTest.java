package com.dezxxx.person.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.person.api.model.CreateIndividualRequestDto;
import com.dezxxx.person.api.model.CreateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.CreateIndividualRequestUserDto;
import com.dezxxx.person.api.model.IndividualResponseDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserDto;
import com.dezxxx.person.entity.AddressEntity;
import com.dezxxx.person.entity.CountryEntity;
import com.dezxxx.person.entity.IndividualEntity;
import com.dezxxx.person.entity.IndividualStatus;
import com.dezxxx.person.entity.UserEntity;
import com.dezxxx.person.mapper.AddressMapper;
import com.dezxxx.person.mapper.IndividualMapper;
import com.dezxxx.person.mapper.UserMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IndividualMapper, UserMapper, AddressMapper")
class IndividualMapperTest {

    private static final UUID INDIVIDUAL_ID = UUID.fromString("7fd6212d-0f71-42a0-bf6e-7dbce6cf04fb");

    private static final UUID USER_ID = UUID.fromString("6c8ec4d0-6fd5-4db8-bf4d-7bcbdb2f6c02");

    private static final UUID ADDRESS_ID = UUID.fromString("6e6d5e5e-5c7d-4f0e-9f9a-34a0b3f8d101");

    private static final Instant CREATED = Instant.parse("2026-06-08T10:15:30Z");

    private final IndividualMapper individualMapper = new IndividualMapper(new UserMapper(new AddressMapper()));

    @Test
    @DisplayName("given a create request, when mapping to entities, then every field lands in individual, user and address")
    void mapsCreateRequest() {
        // given
        CountryEntity ukraine = country("UKR", "UA");
        CreateIndividualRequestDto request = new CreateIndividualRequestDto()
                .passportNumber("123456789")
                .phoneNumber("+380441234567")
                .user(new CreateIndividualRequestUserDto()
                        .email("ivan.petrov@example.org")
                        .firstName("Ivan")
                        .lastName("Petrov")
                        .address(new CreateIndividualRequestUserAddressDto()
                                .city("Kyiv").state("Kyiv").zipCode("01001").addressLine("1 Example St.")));

        // when
        IndividualEntity individual = individualMapper.toIndividualEntity(request, ukraine);

        // then
        assertThat(individual.getPassportNumber()).isEqualTo("123456789");
        assertThat(individual.getPhoneNumber()).isEqualTo("+380441234567");
        assertThat(individual.getUser().getEmail()).isEqualTo("ivan.petrov@example.org");
        assertThat(individual.getUser().getFirstName()).isEqualTo("Ivan");
        assertThat(individual.getUser().getLastName()).isEqualTo("Petrov");
        assertThat(individual.getUser().getAddress().getCountry()).isSameAs(ukraine);
        assertThat(individual.getUser().getAddress().getCity()).isEqualTo("Kyiv");
        assertThat(individual.getUser().getAddress().getState()).isEqualTo("Kyiv");
        assertThat(individual.getUser().getAddress().getZipCode()).isEqualTo("01001");
        assertThat(individual.getUser().getAddress().getAddress()).isEqualTo("1 Example St.");
    }

    @Test
    @DisplayName("given a whole aggregate, when mapping to the response, then the individual is on top, the user and address inside, times in UTC")
    void mapsResponse() {
        // given
        IndividualEntity individual = aggregate();

        // when
        IndividualResponseDto response = individualMapper.toIndividualResponse(individual);

        // then
        assertThat(response.getId()).isEqualTo(INDIVIDUAL_ID);
        assertThat(response.getStatus()).isEqualTo(IndividualResponseDto.StatusEnum.NEW);
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.of(2026, 6, 8, 10, 15, 30, 0, ZoneOffset.UTC));
        assertThat(response.getPassportNumber()).isEqualTo("123456789");
        assertThat(response.getVerifiedAt()).isNull();
        assertThat(response.getUser().getId()).isEqualTo(USER_ID);
        assertThat(response.getUser().getEmail()).isEqualTo("ivan.petrov@example.org");
        assertThat(response.getUser().getFilled()).isTrue();
        assertThat(response.getUser().getCreatedAt()).isEqualTo(OffsetDateTime.of(2026, 6, 8, 10, 15, 30, 0, ZoneOffset.UTC));
        assertThat(response.getUser().getAddress().getId()).isEqualTo(ADDRESS_ID);
        assertThat(response.getUser().getAddress().getCountryAlpha3()).isEqualTo("UKR");
        assertThat(response.getUser().getAddress().getCountryAlpha2()).isEqualTo("UA");
        assertThat(response.getUser().getAddress().getAddressLine()).isEqualTo("1 Example St.");
    }

    @Test
    @DisplayName("given a PATCH with every field, when updating, then individual, user, address and country change")
    void patchesEverything() {
        // given
        IndividualEntity individual = aggregate();
        CountryEntity poland = country("POL", "PL");
        UpdateIndividualRequestDto request = new UpdateIndividualRequestDto()
                .passportNumber("987654321")
                .phoneNumber("+380321234567")
                .user(new UpdateIndividualRequestUserDto()
                        .firstName("Ivan Jr.")
                        .lastName("Petrov-Senior")
                        .address(new UpdateIndividualRequestUserAddressDto()
                                .city("Lviv").state("Lviv").zipCode("79000").addressLine("2 Example St.")));

        // when
        individualMapper.updateIndividual(individual, request, poland);

        // then
        assertThat(individual.getPassportNumber()).isEqualTo("987654321");
        assertThat(individual.getPhoneNumber()).isEqualTo("+380321234567");
        assertThat(individual.getUser().getFirstName()).isEqualTo("Ivan Jr.");
        assertThat(individual.getUser().getLastName()).isEqualTo("Petrov-Senior");
        assertThat(individual.getUser().getAddress().getCountry()).isSameAs(poland);
        assertThat(individual.getUser().getAddress().getCity()).isEqualTo("Lviv");
        assertThat(individual.getUser().getAddress().getState()).isEqualTo("Lviv");
        assertThat(individual.getUser().getAddress().getZipCode()).isEqualTo("79000");
        assertThat(individual.getUser().getAddress().getAddress()).isEqualTo("2 Example St.");
    }

    @Test
    @DisplayName("given an empty PATCH, when updating, then nothing changes: null means not sent")
    void emptyPatchKeepsEverything() {
        // given
        IndividualEntity individual = aggregate();
        CountryEntity ukraine = individual.getUser().getAddress().getCountry();

        // when
        individualMapper.updateIndividual(individual, new UpdateIndividualRequestDto()
                .user(new UpdateIndividualRequestUserDto().address(new UpdateIndividualRequestUserAddressDto())), null);

        // then
        assertThat(individual.getPassportNumber()).isEqualTo("123456789");
        assertThat(individual.getPhoneNumber()).isEqualTo("+380441234567");
        assertThat(individual.getUser().getFirstName()).isEqualTo("Ivan");
        assertThat(individual.getUser().getLastName()).isEqualTo("Petrov");
        assertThat(individual.getUser().getAddress().getCountry()).isSameAs(ukraine);
        assertThat(individual.getUser().getAddress().getCity()).isEqualTo("Kyiv");
        assertThat(individual.getUser().getAddress().getState()).isEqualTo("Kyiv");
        assertThat(individual.getUser().getAddress().getZipCode()).isEqualTo("01001");
        assertThat(individual.getUser().getAddress().getAddress()).isEqualTo("1 Example St.");
    }

    private static IndividualEntity aggregate() {
        AddressEntity address = new AddressEntity();
        address.setId(ADDRESS_ID);
        address.setCountry(country("UKR", "UA"));
        address.setCity("Kyiv");
        address.setState("Kyiv");
        address.setZipCode("01001");
        address.setAddress("1 Example St.");

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setEmail("ivan.petrov@example.org");
        user.setFirstName("Ivan");
        user.setLastName("Petrov");
        user.setFilled(true);
        user.setCreated(CREATED);
        user.setUpdated(CREATED);
        user.setAddress(address);

        IndividualEntity individual = new IndividualEntity();
        individual.setId(INDIVIDUAL_ID);
        individual.setPassportNumber("123456789");
        individual.setPhoneNumber("+380441234567");
        individual.setStatus(IndividualStatus.NEW);
        individual.setCreated(CREATED);
        individual.setUpdated(CREATED);
        individual.setUser(user);
        return individual;
    }

    private static CountryEntity country(String alpha3, String alpha2) {
        CountryEntity country = new CountryEntity();
        country.setAlpha3(alpha3);
        country.setAlpha2(alpha2);
        return country;
    }
}
