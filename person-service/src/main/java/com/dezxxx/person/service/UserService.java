package com.dezxxx.person.service;

import com.dezxxx.person.api.model.CreateUserRequestDto;
import com.dezxxx.person.api.model.UpdateUserRequestDto;
import com.dezxxx.person.api.model.UserResponseDto;
import com.dezxxx.person.entity.CountryEntity;
import com.dezxxx.person.entity.UserEntity;
import com.dezxxx.person.exception.ErrorCode;
import com.dezxxx.person.exception.PersonException;
import com.dezxxx.person.mapper.UserMapper;
import com.dezxxx.person.repository.CountryRepository;
import com.dezxxx.person.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// responses are built inside the transaction, while LAZY associations can still load
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private static final String NEW_INDIVIDUAL_STATUS = "NEW";

    private final UserRepository userRepository;
    private final CountryRepository countryRepository;
    private final UserMapper userMapper;

    @Transactional
    public UserResponseDto createUser(CreateUserRequestDto request) {
        // the unique index on lower(email) still stops two requests racing past this check
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new PersonException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        CountryEntity country = findCountry(
                request.getAddress().getCountryAlpha3(), request.getAddress().getCountryAlpha2());

        UserEntity user = userMapper.toUserEntity(request, country);
        user.getIndividual().setStatus(NEW_INDIVIDUAL_STATUS);
        user.setFilled(true);

        // flush now: created and updated are set at INSERT and the response must carry them
        UserEntity saved = userRepository.saveAndFlush(user);
        log.info("Created user {}", saved.getId());
        return userMapper.toUserResponse(saved);
    }

    public UserResponseDto getUserById(UUID id) {
        UserEntity user = findUser(id);
        log.info("Found user {}", id);
        return userMapper.toUserResponse(user);
    }

    public UserResponseDto getUserByEmail(String email) {
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new PersonException(ErrorCode.USER_NOT_FOUND));
        log.info("Found user {} by email", user.getId());
        return userMapper.toUserResponse(user);
    }

    @Transactional
    public UserResponseDto updateUser(UUID id, UpdateUserRequestDto request) {
        UserEntity user = findUser(id);
        CountryEntity country = request.getAddress() == null
                ? null
                : findCountry(request.getAddress().getCountryAlpha3(), request.getAddress().getCountryAlpha2());

        userMapper.updateUser(user, request, country);
        // write now: the response must carry the new updated time and version
        userRepository.flush();
        log.info("Updated user {}", id);
        return userMapper.toUserResponse(user);
    }

    @Transactional
    public void deleteUser(UUID id) {
        UserEntity user = findUser(id);
        userRepository.delete(user);
        log.info("Deleted user {}", id);
    }

    private UserEntity findUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new PersonException(ErrorCode.USER_NOT_FOUND));
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
