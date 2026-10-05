package com.dezxxx.person.mapper;

import static com.dezxxx.person.util.DateTimeUtil.toUtc;

import com.dezxxx.person.api.model.CreateUserRequestIndividual;
import com.dezxxx.person.api.model.UserResponseIndividual;
import com.dezxxx.person.entity.IndividualEntity;
import org.springframework.stereotype.Component;

@Component
public class IndividualMapper {

    public IndividualEntity toIndividualEntity(CreateUserRequestIndividual request) {
        IndividualEntity individual = new IndividualEntity();
        updateIndividual(individual, request);
        return individual;
    }

    public UserResponseIndividual toIndividualResponse(IndividualEntity individual) {
        return new UserResponseIndividual()
                .id(individual.getId())
                .passportNumber(individual.getPassportNumber())
                .phoneNumber(individual.getPhoneNumber())
                .status(individual.getStatus())
                .verifiedAt(toUtc(individual.getVerifiedAt()))
                .archivedAt(toUtc(individual.getArchivedAt()));
    }

    // PATCH: null means "not sent, leave as is"
    public void updateIndividual(IndividualEntity individual, CreateUserRequestIndividual request) {
        if (request.getPassportNumber() != null) {
            individual.setPassportNumber(request.getPassportNumber());
        }
        if (request.getPhoneNumber() != null) {
            individual.setPhoneNumber(request.getPhoneNumber());
        }
    }
}
