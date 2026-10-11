package com.dezxxx.person.service;

import com.dezxxx.person.api.model.CreateIndividualRequestDto;
import com.dezxxx.person.api.model.CreateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.IndividualResponseDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserAddressDto;
import com.dezxxx.person.entity.CountryEntity;
import com.dezxxx.person.entity.IndividualEntity;
import com.dezxxx.person.entity.IndividualStatus;
import com.dezxxx.person.exception.ErrorCode;
import com.dezxxx.person.exception.PersonException;
import com.dezxxx.person.logging.UserUidLog;
import com.dezxxx.person.mapper.IndividualMapper;
import com.dezxxx.person.repository.CountryRepository;
import com.dezxxx.person.repository.IndividualRepository;
import com.dezxxx.person.repository.UserRepository;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IndividualService {

    private static final String OPERATIONS = "individual.operations";

    private final IndividualRepository individualRepository;
    private final UserRepository userRepository;
    private final CountryRepository countryRepository;
    private final IndividualMapper individualMapper;

    @Transactional
    @Observed(name = OPERATIONS, contextualName = "individualService.create", lowCardinalityKeyValues = {"operation", "create"})
    public IndividualResponseDto createIndividual(CreateIndividualRequestDto request) {
        validateCreate(request);
        CountryEntity country = findCountry(request.getUser().getAddress());
        IndividualEntity individual = newIndividual(request, country);

        // flush now: created and updated are set at INSERT and the response must carry them
        IndividualEntity saved = individualRepository.saveAndFlush(individual);
        UserUidLog.put(saved.getId());
        log.info("Created individual {}", saved.getId());
        return individualMapper.toIndividualResponse(saved);
    }

    @Observed(name = OPERATIONS, contextualName = "individualService.getById", lowCardinalityKeyValues = {"operation", "getById"})
    public IndividualResponseDto getIndividualById(UUID id) {
        UserUidLog.put(id);
        IndividualEntity individual = findIndividual(id);
        log.info("Found individual {}", id);
        return individualMapper.toIndividualResponse(individual);
    }

    @Observed(name = OPERATIONS, contextualName = "individualService.getByEmail", lowCardinalityKeyValues = {"operation", "getByEmail"})
    public IndividualResponseDto getIndividualByEmail(String email) {
        IndividualEntity individual = individualRepository.findActiveByEmail(email)
                .orElseThrow(() -> new PersonException(ErrorCode.INDIVIDUAL_NOT_FOUND));
        UserUidLog.put(individual.getId());
        log.info("Found individual {} by email", individual.getId());
        return individualMapper.toIndividualResponse(individual);
    }

    @Transactional
    @Observed(name = OPERATIONS, contextualName = "individualService.update", lowCardinalityKeyValues = {"operation", "update"})
    public IndividualResponseDto updateIndividual(UUID id, UpdateIndividualRequestDto request) {
        UserUidLog.put(id);
        IndividualEntity individual = findIndividual(id);
        CountryEntity country = findCountry(newAddressOf(request));

        individualMapper.updateIndividual(individual, request, country);
        // write now: the response must carry the new updated time and version
        individualRepository.flush();
        log.info("Updated individual {}", id);
        return individualMapper.toIndividualResponse(individual);
    }

    @Transactional
    @Observed(name = OPERATIONS, contextualName = "individualService.delete", lowCardinalityKeyValues = {"operation", "delete"})
    public void deleteIndividual(UUID id) {
        UserUidLog.put(id);
        IndividualEntity individual = findIndividual(id);
        individual.setStatus(IndividualStatus.ARCHIVED);
        individual.setArchivedAt(Instant.now());
        log.info("Archived individual {}", id);
    }

    @Transactional
    @Observed(name = OPERATIONS, contextualName = "individualService.compensateRegistration", lowCardinalityKeyValues = {"operation", "compensateRegistration"})
    public void compensateRegistration(UUID id) {
        UserUidLog.put(id);
        IndividualEntity individual = individualRepository.findById(id)
                .orElseThrow(() -> new PersonException(ErrorCode.INDIVIDUAL_NOT_FOUND));
        individualRepository.delete(individual);
        log.info("Deleted individual {} to compensate its registration", id);
    }

    private void validateCreate(CreateIndividualRequestDto request) {
        // the unique index on lower(email) still stops two requests racing past this check
        if (userRepository.existsByEmail(request.getUser().getEmail())) {
            throw new PersonException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    private IndividualEntity newIndividual(CreateIndividualRequestDto request, CountryEntity country) {
        IndividualEntity individual = individualMapper.toIndividualEntity(request, country);
        individual.setStatus(IndividualStatus.NEW);
        individual.getUser().setFilled(true);
        return individual;
    }

    private IndividualEntity findIndividual(UUID id) {
        return individualRepository.findActiveById(id)
                .orElseThrow(() -> new PersonException(ErrorCode.INDIVIDUAL_NOT_FOUND));
    }

    private static UpdateIndividualRequestUserAddressDto newAddressOf(UpdateIndividualRequestDto request) {
        return request.getUser() == null ? null : request.getUser().getAddress();
    }

    private CountryEntity findCountry(CreateIndividualRequestUserAddressDto address) {
        return findCountry(address.getCountryAlpha3(), address.getCountryAlpha2());
    }

    private CountryEntity findCountry(UpdateIndividualRequestUserAddressDto address) {
        return address == null ? null : findCountry(address.getCountryAlpha3(), address.getCountryAlpha2());
    }

    private CountryEntity findCountry(String alpha3, String alpha2) {
        if (alpha3 != null) {
            return countryRepository.findByAlpha3(alpha3)
                    .orElseThrow(() -> new PersonException(ErrorCode.COUNTRY_NOT_FOUND, "Unknown country code: " + alpha3));
        }
        if (alpha2 != null) {
            return countryRepository.findByAlpha2(alpha2)
                    .orElseThrow(() -> new PersonException(ErrorCode.COUNTRY_NOT_FOUND, "Unknown country code: " + alpha2));
        }
        return null;
    }
}
