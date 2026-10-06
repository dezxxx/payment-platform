package com.dezxxx.person.mapper;

import static com.dezxxx.person.util.DateTimeUtil.toUtc;

import com.dezxxx.person.api.model.CreateUserRequestIndividualDto;
import com.dezxxx.person.api.model.UserResponseIndividualDto;
import com.dezxxx.person.entity.IndividualEntity;
import org.springframework.stereotype.Component;

@Component
public class IndividualMapper {

    public IndividualEntity toIndividualEntity(CreateUserRequestIndividualDto request) {
        IndividualEntity individual = new IndividualEntity();
        updateIndividual(individual, request);
        return individual;
    }

    public UserResponseIndividualDto toIndividualResponse(IndividualEntity individual) {
        return new UserResponseIndividualDto()
                .id(individual.getId())
                .passportNumber(individual.getPassportNumber())
                .phoneNumber(individual.getPhoneNumber())
                .status(individual.getStatus())
                .verifiedAt(toUtc(individual.getVerifiedAt()))
                .archivedAt(toUtc(individual.getArchivedAt()));
    }

    // PATCH: null means "not sent, leave as is"
    public void updateIndividual(IndividualEntity individual, CreateUserRequestIndividualDto request) {
        if (request.getPassportNumber() != null) {
            individual.setPassportNumber(request.getPassportNumber());
        }
        if (request.getPhoneNumber() != null) {
            individual.setPhoneNumber(request.getPhoneNumber());
        }
    }
}
