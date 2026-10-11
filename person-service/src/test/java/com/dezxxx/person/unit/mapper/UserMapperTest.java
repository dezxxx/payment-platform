package com.dezxxx.person.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.person.api.model.CreateUserRequestAddressDto;
import com.dezxxx.person.api.model.CreateUserRequestDto;
import com.dezxxx.person.api.model.CreateUserRequestIndividualDto;
import com.dezxxx.person.api.model.UpdateUserRequestAddressDto;
import com.dezxxx.person.api.model.UpdateUserRequestDto;
import com.dezxxx.person.api.model.UserResponseDto;
import com.dezxxx.person.entity.AddressEntity;
import com.dezxxx.person.entity.CountryEntity;
import com.dezxxx.person.entity.IndividualEntity;
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

@DisplayName("UserMapper, AddressMapper, IndividualMapper")
class UserMapperTest {

    private static final UUID USER_ID = UUID.fromString("6c8ec4d0-6fd5-4db8-bf4d-7bcbdb2f6c02");

    private static final UUID ADDRESS_ID = UUID.fromString("6e6d5e5e-5c7d-4f0e-9f9a-34a0b3f8d101");

    private static final UUID INDIVIDUAL_ID = UUID.fromString("7fd6212d-0f71-42a0-bf6e-7dbce6cf04fb");

    private static final Instant CREATED = Instant.parse("2026-06-08T10:15:30Z");

    private final UserMapper userMapper = new UserMapper(new AddressMapper(), new IndividualMapper());

    @Test
    @DisplayName("given a create request, when mapping to entities, then every field lands in user, address and individual")
    void mapsCreateRequest() {
        // given
        CountryEntity ukraine = country("UKR", "UA");
        CreateUserRequestDto request = new CreateUserRequestDto()
                .email("ivan.petrov@example.org")
                .firstName("Ivan")
                .lastName("Petrov")
                .address(new CreateUserRequestAddressDto()
                        .city("Kyiv").state("Kyiv").zipCode("01001").addressLine("1 Example St."))
                .individual(new CreateUserRequestIndividualDto()
                        .passportNumber("123456789").phoneNumber("+380441234567"));

        // when
        UserEntity user = userMapper.toUserEntity(request, ukraine);

        // then
        assertThat(user.getEmail()).isEqualTo("ivan.petrov@example.org");
        assertThat(user.getFirstName()).isEqualTo("Ivan");
        assertThat(user.getLastName()).isEqualTo("Petrov");
        assertThat(user.getAddress().getCountry()).isSameAs(ukraine);
        assertThat(user.getAddress().getCity()).isEqualTo("Kyiv");
        assertThat(user.getAddress().getState()).isEqualTo("Kyiv");
        assertThat(user.getAddress().getZipCode()).isEqualTo("01001");
        assertThat(user.getAddress().getAddress()).isEqualTo("1 Example St.");
        assertThat(user.getIndividual().getPassportNumber()).isEqualTo("123456789");
        assertThat(user.getIndividual().getPhoneNumber()).isEqualTo("+380441234567");
        assertThat(user.getIndividual().getUser()).isSameAs(user);
    }

    @Test
    @DisplayName("given a create request without address and individual, when mapping, then the user stands alone")
    void mapsBareCreateRequest() {
        // given
        CreateUserRequestDto request = new CreateUserRequestDto().email("ivan.petrov@example.org");

        // when
        UserEntity user = userMapper.toUserEntity(request, null);

        // then
        assertThat(user.getAddress()).isNull();
        assertThat(user.getIndividual()).isNull();
    }

    @Test
    @DisplayName("given a whole aggregate, when mapping to the response, then the fields and both country codes are there and times are UTC")
    void mapsResponse() {
        // given
        UserEntity user = aggregate();

        // when
        UserResponseDto response = userMapper.toUserResponse(user);

        // then
        assertThat(response.getId()).isEqualTo(USER_ID);
        assertThat(response.getEmail()).isEqualTo("ivan.petrov@example.org");
        assertThat(response.getFilled()).isTrue();
        assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.of(2026, 6, 8, 10, 15, 30, 0, ZoneOffset.UTC));
        assertThat(response.getAddress().getId()).isEqualTo(ADDRESS_ID);
        assertThat(response.getAddress().getCountryAlpha3()).isEqualTo("UKR");
        assertThat(response.getAddress().getCountryAlpha2()).isEqualTo("UA");
        assertThat(response.getAddress().getAddressLine()).isEqualTo("1 Example St.");
        assertThat(response.getIndividual().getId()).isEqualTo(INDIVIDUAL_ID);
        assertThat(response.getIndividual().getStatus()).isEqualTo("NEW");
        assertThat(response.getIndividual().getVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("given a user without address, individual and country, when mapping to the response, then those parts are absent")
    void mapsBareResponse() {
        // given
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        AddressEntity address = new AddressEntity();
        address.setCity("Kyiv");
        UserEntity withAddressOnly = new UserEntity();
        withAddressOnly.setAddress(address);

        // when
        UserResponseDto bare = userMapper.toUserResponse(user);
        UserResponseDto noCountry = userMapper.toUserResponse(withAddressOnly);

        // then
        assertThat(bare.getAddress()).isNull();
        assertThat(bare.getIndividual()).isNull();
        assertThat(noCountry.getAddress().getCountryAlpha3()).isNull();
    }

    @Test
    @DisplayName("given a PATCH with every field, when updating, then user, address, country and individual change")
    void patchesEverything() {
        // given
        UserEntity user = aggregate();
        CountryEntity poland = country("POL", "PL");
        UpdateUserRequestDto request = new UpdateUserRequestDto()
                .firstName("Ivan Jr.")
                .lastName("Petrov-Senior")
                .address(new UpdateUserRequestAddressDto()
                        .city("Lviv").state("Lviv").zipCode("79000").addressLine("2 Example St."))
                .individual(new CreateUserRequestIndividualDto()
                        .passportNumber("987654321").phoneNumber("+380321234567"));

        // when
        userMapper.updateUser(user, request, poland);

        // then
        assertThat(user.getFirstName()).isEqualTo("Ivan Jr.");
        assertThat(user.getLastName()).isEqualTo("Petrov-Senior");
        assertThat(user.getAddress().getCountry()).isSameAs(poland);
        assertThat(user.getAddress().getCity()).isEqualTo("Lviv");
        assertThat(user.getAddress().getState()).isEqualTo("Lviv");
        assertThat(user.getAddress().getZipCode()).isEqualTo("79000");
        assertThat(user.getAddress().getAddress()).isEqualTo("2 Example St.");
        assertThat(user.getIndividual().getPassportNumber()).isEqualTo("987654321");
        assertThat(user.getIndividual().getPhoneNumber()).isEqualTo("+380321234567");
    }

    @Test
    @DisplayName("given an empty PATCH, when updating, then nothing changes: null means not sent")
    void emptyPatchKeepsEverything() {
        // given
        UserEntity user = aggregate();
        CountryEntity ukraine = user.getAddress().getCountry();

        // when
        userMapper.updateUser(user, new UpdateUserRequestDto()
                .address(new UpdateUserRequestAddressDto())
                .individual(new CreateUserRequestIndividualDto()), null);

        // then
        assertThat(user.getFirstName()).isEqualTo("Ivan");
        assertThat(user.getLastName()).isEqualTo("Petrov");
        assertThat(user.getAddress().getCountry()).isSameAs(ukraine);
        assertThat(user.getAddress().getCity()).isEqualTo("Kyiv");
        assertThat(user.getAddress().getState()).isEqualTo("Kyiv");
        assertThat(user.getAddress().getZipCode()).isEqualTo("01001");
        assertThat(user.getAddress().getAddress()).isEqualTo("1 Example St.");
        assertThat(user.getIndividual().getPassportNumber()).isEqualTo("123456789");
        assertThat(user.getIndividual().getPhoneNumber()).isEqualTo("+380441234567");
    }

    private static UserEntity aggregate() {
        AddressEntity address = new AddressEntity();
        address.setId(ADDRESS_ID);
        address.setCountry(country("UKR", "UA"));
        address.setCity("Kyiv");
        address.setState("Kyiv");
        address.setZipCode("01001");
        address.setAddress("1 Example St.");

        IndividualEntity individual = new IndividualEntity();
        individual.setId(INDIVIDUAL_ID);
        individual.setPassportNumber("123456789");
        individual.setPhoneNumber("+380441234567");
        individual.setStatus("NEW");

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setEmail("ivan.petrov@example.org");
        user.setFirstName("Ivan");
        user.setLastName("Petrov");
        user.setFilled(true);
        user.setCreated(CREATED);
        user.setUpdated(CREATED);
        user.setAddress(address);
        user.setIndividual(individual);
        return user;
    }

    private static CountryEntity country(String alpha3, String alpha2) {
        CountryEntity country = new CountryEntity();
        country.setAlpha3(alpha3);
        country.setAlpha2(alpha2);
        return country;
    }
}
