package com.dezxxx.person.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dezxxx.person.api.model.CreateIndividualRequestDto;
import com.dezxxx.person.api.model.CreateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.CreateIndividualRequestUserDto;
import com.dezxxx.person.api.model.IndividualResponseDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserDto;
import com.dezxxx.person.entity.CountryEntity;
import com.dezxxx.person.entity.IndividualEntity;
import com.dezxxx.person.entity.IndividualStatus;
import com.dezxxx.person.entity.UserEntity;
import com.dezxxx.person.exception.ErrorCode;
import com.dezxxx.person.exception.PersonException;
import com.dezxxx.person.mapper.IndividualMapper;
import com.dezxxx.person.repository.CountryRepository;
import com.dezxxx.person.repository.IndividualRepository;
import com.dezxxx.person.repository.UserRepository;
import com.dezxxx.person.service.IndividualService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("IndividualService")
class IndividualServiceTest {

    private static final UUID INDIVIDUAL_ID = UUID.fromString("7fd6212d-0f71-42a0-bf6e-7dbce6cf04fb");

    private static final String EMAIL = "ivan.petrov@example.org";

    @Mock
    private IndividualRepository individualRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CountryRepository countryRepository;

    @Mock
    private IndividualMapper individualMapper;

    @InjectMocks
    private IndividualService individualService;

    @Test
    @DisplayName("given a free email, when creating, then the individual is NEW, its user filled and the aggregate saved at once")
    void createsIndividual() {
        // given
        CreateIndividualRequestDto request = createRequest("UKR", "UA");
        CountryEntity ukraine = country("UKR", "UA");
        IndividualEntity individual = individualWithUser();
        IndividualResponseDto response = new IndividualResponseDto().id(INDIVIDUAL_ID);
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(countryRepository.findByAlpha3("UKR")).thenReturn(Optional.of(ukraine));
        when(individualMapper.toIndividualEntity(request, ukraine)).thenReturn(individual);
        when(individualRepository.saveAndFlush(individual)).thenReturn(individual);
        when(individualMapper.toIndividualResponse(individual)).thenReturn(response);

        // when
        IndividualResponseDto result = individualService.createIndividual(request);

        // then
        assertThat(result).isSameAs(response);
        assertThat(individual.getStatus()).isEqualTo(IndividualStatus.NEW);
        assertThat(individual.getUser().isFilled()).isTrue();
        verify(individualRepository).saveAndFlush(individual);
    }

    @Test
    @DisplayName("given the email is taken, when creating, then EMAIL_ALREADY_EXISTS and nothing is saved")
    void refusesTakenEmail() {
        // given
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> individualService.createIndividual(createRequest("UKR", null)))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);

        // then
        verify(individualRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("given both country codes, when creating, then alpha3 decides and alpha2 is not looked up")
    void alpha3Wins() {
        // given
        CountryEntity ukraine = country("UKR", "UA");
        IndividualEntity individual = individualWithUser();
        CreateIndividualRequestDto request = createRequest("UKR", "PL");
        when(countryRepository.findByAlpha3("UKR")).thenReturn(Optional.of(ukraine));
        when(individualMapper.toIndividualEntity(request, ukraine)).thenReturn(individual);
        when(individualRepository.saveAndFlush(individual)).thenReturn(individual);

        // when
        individualService.createIndividual(request);

        // then
        verify(countryRepository, never()).findByAlpha2(anyString());
    }

    @Test
    @DisplayName("given a PATCH with only alpha2, when updating, then the country is found by alpha2")
    void alpha2WhenNoAlpha3() {
        // given
        IndividualEntity individual = individualWithUser();
        CountryEntity poland = country("POL", "PL");
        UpdateIndividualRequestDto request = new UpdateIndividualRequestDto()
                .user(new UpdateIndividualRequestUserDto()
                        .address(new UpdateIndividualRequestUserAddressDto().countryAlpha2("PL")));
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.of(individual));
        when(countryRepository.findByAlpha2("PL")).thenReturn(Optional.of(poland));

        // when
        individualService.updateIndividual(INDIVIDUAL_ID, request);

        // then
        verify(individualMapper).updateIndividual(individual, request, poland);
    }

    @Test
    @DisplayName("given an unknown alpha3, when creating, then COUNTRY_NOT_FOUND names the code")
    void unknownAlpha3() {
        // given
        when(countryRepository.findByAlpha3("XXX")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> individualService.createIndividual(createRequest("XXX", null)))
                .isInstanceOf(PersonException.class)
                .hasMessageContaining("XXX")
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COUNTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("given an unknown alpha2 in a PATCH, when updating, then COUNTRY_NOT_FOUND names the code")
    void unknownAlpha2() {
        // given
        UpdateIndividualRequestDto request = new UpdateIndividualRequestDto()
                .user(new UpdateIndividualRequestUserDto()
                        .address(new UpdateIndividualRequestUserAddressDto().countryAlpha2("XX")));
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.of(individualWithUser()));
        when(countryRepository.findByAlpha2("XX")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> individualService.updateIndividual(INDIVIDUAL_ID, request))
                .isInstanceOf(PersonException.class)
                .hasMessageContaining("XX")
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COUNTRY_NOT_FOUND);
    }

    @Test
    @DisplayName("given an existing id, when reading by id, then the aggregate is mapped to the response")
    void getsById() {
        // given
        IndividualEntity individual = individualWithUser();
        IndividualResponseDto response = new IndividualResponseDto().id(INDIVIDUAL_ID);
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.of(individual));
        when(individualMapper.toIndividualResponse(individual)).thenReturn(response);

        // when
        IndividualResponseDto result = individualService.getIndividualById(INDIVIDUAL_ID);

        // then
        assertThat(result).isSameAs(response);
    }

    @Test
    @DisplayName("given an unknown id, when reading by id, then INDIVIDUAL_NOT_FOUND")
    void getByIdNotFound() {
        // given
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> individualService.getIndividualById(INDIVIDUAL_ID))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INDIVIDUAL_NOT_FOUND);
    }

    @Test
    @DisplayName("given an existing email, when reading by email, then the aggregate is mapped to the response")
    void getsByEmail() {
        // given
        IndividualEntity individual = individualWithUser();
        IndividualResponseDto response = new IndividualResponseDto().id(INDIVIDUAL_ID);
        when(individualRepository.findActiveByEmail(EMAIL)).thenReturn(Optional.of(individual));
        when(individualMapper.toIndividualResponse(individual)).thenReturn(response);

        // when
        IndividualResponseDto result = individualService.getIndividualByEmail(EMAIL);

        // then
        assertThat(result).isSameAs(response);
    }

    @Test
    @DisplayName("given an unknown email, when reading by email, then INDIVIDUAL_NOT_FOUND")
    void getByEmailNotFound() {
        // given
        when(individualRepository.findActiveByEmail(EMAIL)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> individualService.getIndividualByEmail(EMAIL))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INDIVIDUAL_NOT_FOUND);
    }

    @Test
    @DisplayName("given a PATCH without a user, when updating, then no country is looked up and the change is flushed")
    void updatesWithoutUser() {
        // given
        IndividualEntity individual = individualWithUser();
        UpdateIndividualRequestDto request = new UpdateIndividualRequestDto().phoneNumber("+380441234568");
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.of(individual));

        // when
        individualService.updateIndividual(INDIVIDUAL_ID, request);

        // then
        verify(individualMapper).updateIndividual(individual, request, null);
        verify(individualRepository).flush();
        verify(countryRepository, never()).findByAlpha3(anyString());
        verify(countryRepository, never()).findByAlpha2(anyString());
    }

    @Test
    @DisplayName("given a PATCH of the user without an address, when updating, then no country is looked up")
    void updatesUserWithoutAddress() {
        // given
        IndividualEntity individual = individualWithUser();
        UpdateIndividualRequestDto request = new UpdateIndividualRequestDto()
                .user(new UpdateIndividualRequestUserDto().lastName("Petrov-Senior"));
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.of(individual));

        // when
        individualService.updateIndividual(INDIVIDUAL_ID, request);

        // then
        verify(individualMapper).updateIndividual(individual, request, null);
        verify(countryRepository, never()).findByAlpha3(anyString());
    }

    @Test
    @DisplayName("given a PATCH with a country, when updating, then the new country goes to the mapper")
    void updatesWithCountry() {
        // given
        IndividualEntity individual = individualWithUser();
        CountryEntity poland = country("POL", "PL");
        UpdateIndividualRequestDto request = new UpdateIndividualRequestDto()
                .user(new UpdateIndividualRequestUserDto()
                        .address(new UpdateIndividualRequestUserAddressDto().countryAlpha3("POL")));
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.of(individual));
        when(countryRepository.findByAlpha3("POL")).thenReturn(Optional.of(poland));

        // when
        individualService.updateIndividual(INDIVIDUAL_ID, request);

        // then
        verify(individualMapper).updateIndividual(individual, request, poland);
    }

    @Test
    @DisplayName("given an unknown id, when updating, then INDIVIDUAL_NOT_FOUND and nothing is written")
    void updateNotFound() {
        // given
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> individualService.updateIndividual(INDIVIDUAL_ID, new UpdateIndividualRequestDto()))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INDIVIDUAL_NOT_FOUND);

        // then
        verify(individualRepository, never()).flush();
    }

    @Test
    @DisplayName("given an active individual, when deleting, then it is archived and no row is deleted")
    void archives() {
        // given
        IndividualEntity individual = individualWithUser();
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.of(individual));

        // when
        individualService.deleteIndividual(INDIVIDUAL_ID);

        // then
        assertThat(individual.getStatus()).isEqualTo(IndividualStatus.ARCHIVED);
        assertThat(individual.getArchivedAt()).isNotNull();
        verify(individualRepository, never()).delete(any());
    }

    @Test
    @DisplayName("given an unknown or archived id, when deleting, then INDIVIDUAL_NOT_FOUND")
    void deleteNotFound() {
        // given
        when(individualRepository.findActiveById(INDIVIDUAL_ID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> individualService.deleteIndividual(INDIVIDUAL_ID))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INDIVIDUAL_NOT_FOUND);

        // then
        verify(individualRepository, never()).delete(any());
    }

    @Test
    @DisplayName("given an individual of any status, when compensating its registration, then the aggregate is deleted")
    void compensates() {
        // given
        IndividualEntity individual = individualWithUser();
        when(individualRepository.findById(INDIVIDUAL_ID)).thenReturn(Optional.of(individual));

        // when
        individualService.compensateRegistration(INDIVIDUAL_ID);

        // then
        verify(individualRepository).delete(individual);
    }

    @Test
    @DisplayName("given an unknown id, when compensating, then INDIVIDUAL_NOT_FOUND and nothing is deleted")
    void compensateNotFound() {
        // given
        when(individualRepository.findById(INDIVIDUAL_ID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> individualService.compensateRegistration(INDIVIDUAL_ID))
                .isInstanceOf(PersonException.class)
                .extracting(ex -> ((PersonException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INDIVIDUAL_NOT_FOUND);

        // then
        verify(individualRepository, never()).delete(any());
    }

    private static CreateIndividualRequestDto createRequest(String alpha3, String alpha2) {
        return new CreateIndividualRequestDto()
                .passportNumber("123456789")
                .phoneNumber("+380441234567")
                .user(new CreateIndividualRequestUserDto()
                        .email(EMAIL)
                        .firstName("Ivan")
                        .lastName("Petrov")
                        .address(new CreateIndividualRequestUserAddressDto()
                                .countryAlpha3(alpha3)
                                .countryAlpha2(alpha2)
                                .city("Kyiv")
                                .addressLine("1 Example St.")));
    }

    private static CountryEntity country(String alpha3, String alpha2) {
        CountryEntity country = new CountryEntity();
        country.setAlpha3(alpha3);
        country.setAlpha2(alpha2);
        return country;
    }

    private static IndividualEntity individualWithUser() {
        UserEntity user = new UserEntity();
        user.setEmail(EMAIL);
        IndividualEntity individual = new IndividualEntity();
        individual.setId(INDIVIDUAL_ID);
        individual.setUser(user);
        return individual;
    }
}
