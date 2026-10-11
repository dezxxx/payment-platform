package com.dezxxx.person.mapper;

import static com.dezxxx.person.util.DateTimeUtil.toUtc;

import com.dezxxx.person.api.model.CreateIndividualRequestDto;
import com.dezxxx.person.api.model.IndividualResponseDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestDto;
import com.dezxxx.person.entity.CountryEntity;
import com.dezxxx.person.entity.IndividualEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IndividualMapper {

    private final UserMapper userMapper;

    public IndividualEntity toIndividualEntity(CreateIndividualRequestDto request, CountryEntity country) {
        IndividualEntity individual = new IndividualEntity();
        individual.setPassportNumber(request.getPassportNumber());
        individual.setPhoneNumber(request.getPhoneNumber());
        individual.setUser(userMapper.toUserEntity(request.getUser(), country));
        return individual;
    }

    public IndividualResponseDto toIndividualResponse(IndividualEntity individual) {
        return new IndividualResponseDto()
                .id(individual.getId())
                .passportNumber(individual.getPassportNumber())
                .phoneNumber(individual.getPhoneNumber())
                .status(IndividualResponseDto.StatusEnum.fromValue(individual.getStatus().name()))
                .createdAt(toUtc(individual.getCreated()))
                .updatedAt(toUtc(individual.getUpdated()))
                .verifiedAt(toUtc(individual.getVerifiedAt()))
                .archivedAt(toUtc(individual.getArchivedAt()))
                .user(userMapper.toUserResponse(individual.getUser()));
    }

    public void updateIndividual(IndividualEntity individual, UpdateIndividualRequestDto request, CountryEntity country) {
        if (request.getPassportNumber() != null) {
            individual.setPassportNumber(request.getPassportNumber());
        }
        if (request.getPhoneNumber() != null) {
            individual.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getUser() != null) {
            userMapper.updateUser(individual.getUser(), request.getUser(), country);
        }
    }
}
