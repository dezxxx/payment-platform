package com.dezxxx.person.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dezxxx.person.api.model.CreateUserRequestAddressDto;
import com.dezxxx.person.api.model.CreateUserRequestDto;
import com.dezxxx.person.api.model.CreateUserRequestIndividualDto;
import com.dezxxx.person.api.model.UpdateUserRequestAddressDto;
import com.dezxxx.person.api.model.UpdateUserRequestDto;
import com.dezxxx.person.api.model.UserResponseDto;
import com.dezxxx.person.entity.CountryEntity;
import com.dezxxx.person.entity.IndividualEntity;
import com.dezxxx.person.entity.UserEntity;
import com.dezxxx.person.exception.ErrorCode;
import com.dezxxx.person.exception.PersonException;
import com.dezxxx.person.mapper.UserMapper;
import com.dezxxx.person.repository.CountryRepository;
import com.dezxxx.person.repository.UserRepository;
import com.dezxxx.person.service.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

    private static final UUID USER_ID = UUID.fromString("6c8ec4d0-6fd5-4db8-bf4d-7bcbdb2f6c02");

    private static final String EMAIL = "ivan.petrov@example.org";

    @Mock
    private UserRepository userRepository;

    @Mock
    private CountryRepository countryRepository;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("given a free email, when creating, then the individual is NEW, the profile filled and the user saved at once")
    void createsUser() {
        // given
        CreateUserRequestDto request = createRequest("UKR", "UA");
        CountryEntity ukraine = country("UKR", "UA");
        UserEntity user = userWithIndividual();
        UserResponseDto response = new UserResponseDto().id(USER_ID);
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(countryRepository.findByAlpha3("UKR")).thenReturn(Optional.of(ukraine));
        when(userMapper.toUserEntity(request, ukraine)).thenReturn(user);
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        when(userMapper.toUserResponse(user)).thenReturn(response);

        // when
        UserResponseDto result = userService.createUser(request);

        // then
        assertThat(result).isSameAs(response);
        assertThat(user.getIndividual().getStatus()).isEqualTo("NEW");
        assertThat(user.isFilled()).isTrue();
        verify(userRepository).saveAndFlush(user);
    }

    @Test
    @DisplayName("given the email is taken, when creating, then EMAIL_ALREADY_EXISTS and nothing is saved")
    void refusesTakenEmail() {
        // given
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> userService.createUser(createRequest("UKR", null)))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);

        // then
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("given both country codes, when creating, then alpha3 decides and alpha2 is not looked up")
    void alpha3Wins() {
        // given
        CountryEntity ukraine = country("UKR", "UA");
        UserEntity user = userWithIndividual();
        CreateUserRequestDto request = createRequest("UKR", "PL");
        when(countryRepository.findByAlpha3("UKR")).thenReturn(Optional.of(ukraine));
        when(userMapper.toUserEntity(request, ukraine)).thenReturn(user);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        // when
        userService.createUser(request);

        // then
        verify(countryRepository, never()).findByAlpha2(anyString());
    }

    @Test
    @DisplayName("given only alpha2, when creating, then the country is found by alpha2")
    void alpha2WhenNoAlpha3() {
        // given
        CountryEntity ukraine = country("UKR", "UA");
        UserEntity user = userWithIndividual();
        CreateUserRequestDto request = createRequest(null, "UA");
        when(countryRepository.findByAlpha2("UA")).thenReturn(Optional.of(ukraine));
        when(userMapper.toUserEntity(request, ukraine)).thenReturn(user);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        // when
        userService.createUser(request);

        // then
        verify(userMapper).toUserEntity(request, ukraine);
    }

    @Test
    @DisplayName("given no country code, when creating, then the user is saved without a country")
    void noCountryCode() {
        // given
        UserEntity user = userWithIndividual();
        CreateUserRequestDto request = createRequest(null, null);
        when(userMapper.toUserEntity(request, null)).thenReturn(user);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        // when
        userService.createUser(request);

        // then
        verify(userMapper).toUserEntity(request, null);
    }

    @Test
    @DisplayName("given an unknown alpha3, when creating, then COUNTRY_NOT_FOUND names the code")
    void unknownAlpha3() {
        // given
        when(countryRepository.findByAlpha3("XXX")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> userService.createUser(createRequest("XXX", null)))
                .isInstanceOf(PersonException.class)
                .hasMessageContaining("XXX")
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COUNTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("given an unknown alpha2, when creating, then COUNTRY_NOT_FOUND names the code")
    void unknownAlpha2() {
        // given
        when(countryRepository.findByAlpha2("XX")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> userService.createUser(createRequest(null, "XX")))
                .isInstanceOf(PersonException.class)
                .hasMessageContaining("XX")
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COUNTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("given an existing id, when reading by id, then the aggregate is mapped to the response")
    void getsById() {
        // given
        UserEntity user = userWithIndividual();
        UserResponseDto response = new UserResponseDto().id(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userMapper.toUserResponse(user)).thenReturn(response);

        // when
        UserResponseDto result = userService.getUserById(USER_ID);

        // then
        assertThat(result).isSameAs(response);
    }

    @Test
    @DisplayName("given an unknown id, when reading by id, then USER_NOT_FOUND")
    void getByIdNotFound() {
        // given
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> userService.getUserById(USER_ID))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("given an existing email, when reading by email, then the aggregate is mapped to the response")
    void getsByEmail() {
        // given
        UserEntity user = userWithIndividual();
        UserResponseDto response = new UserResponseDto().id(USER_ID);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userMapper.toUserResponse(user)).thenReturn(response);

        // when
        UserResponseDto result = userService.getUserByEmail(EMAIL);

        // then
        assertThat(result).isSameAs(response);
    }

    @Test
    @DisplayName("given an unknown email, when reading by email, then USER_NOT_FOUND")
    void getByEmailNotFound() {
        // given
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> userService.getUserByEmail(EMAIL))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("given a PATCH without an address, when updating, then no country is looked up and the change is flushed")
    void updatesWithoutAddress() {
        // given
        UserEntity user = userWithIndividual();
        UpdateUserRequestDto request = new UpdateUserRequestDto().lastName("Petrov-Senior");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        // when
        userService.updateUser(USER_ID, request);

        // then
        verify(userMapper).updateUser(user, request, null);
        verify(userRepository).flush();
        verify(countryRepository, never()).findByAlpha3(anyString());
        verify(countryRepository, never()).findByAlpha2(anyString());
    }

    @Test
    @DisplayName("given a PATCH with a country, when updating, then the new country goes to the mapper")
    void updatesWithCountry() {
        // given
        UserEntity user = userWithIndividual();
        CountryEntity poland = country("POL", "PL");
        UpdateUserRequestDto request = new UpdateUserRequestDto()
                .address(new UpdateUserRequestAddressDto().countryAlpha3("POL"));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(countryRepository.findByAlpha3("POL")).thenReturn(Optional.of(poland));

        // when
        userService.updateUser(USER_ID, request);

        // then
        verify(userMapper).updateUser(user, request, poland);
    }

    @Test
    @DisplayName("given an unknown id, when updating, then USER_NOT_FOUND and nothing is written")
    void updateNotFound() {
        // given
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> userService.updateUser(USER_ID, new UpdateUserRequestDto()))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        // then
        verify(userRepository, never()).flush();
    }

    @Test
    @DisplayName("given an existing id, when deleting, then the aggregate is deleted")
    void deletes() {
        // given
        UserEntity user = userWithIndividual();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        // when
        userService.deleteUser(USER_ID);

        // then
        verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("given an unknown id, when deleting, then USER_NOT_FOUND and nothing is deleted")
    void deleteNotFound() {
        // given
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> userService.deleteUser(USER_ID))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        // then
        verify(userRepository, never()).delete(any());
    }

    private static CreateUserRequestDto createRequest(String alpha3, String alpha2) {
        return new CreateUserRequestDto()
                .email(EMAIL)
                .firstName("Ivan")
                .lastName("Petrov")
                .address(new CreateUserRequestAddressDto()
                        .countryAlpha3(alpha3)
                        .countryAlpha2(alpha2)
                        .city("Kyiv")
                        .addressLine("1 Example St."))
                .individual(new CreateUserRequestIndividualDto()
                        .passportNumber("123456789")
                        .phoneNumber("+380441234567"));
    }

    private static CountryEntity country(String alpha3, String alpha2) {
        CountryEntity country = new CountryEntity();
        country.setAlpha3(alpha3);
        country.setAlpha2(alpha2);
        return country;
    }

    private static UserEntity userWithIndividual() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setEmail(EMAIL);
        user.setIndividual(new IndividualEntity());
        return user;
    }
}
