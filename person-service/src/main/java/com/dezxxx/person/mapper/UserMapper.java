package com.dezxxx.person.mapper;

import static com.dezxxx.person.util.DateTimeUtil.toUtc;

import com.dezxxx.person.api.model.CreateUserRequestDto;
import com.dezxxx.person.api.model.UpdateUserRequestDto;
import com.dezxxx.person.api.model.UserResponseDto;
import com.dezxxx.person.entity.CountryEntity;
import com.dezxxx.person.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// The aggregate as a whole: the user's own fields here, address and individual
// through their mappers. No database calls and no business decisions: the country
// is looked up by the service and handed in, status and filled are set there too
@Component
@RequiredArgsConstructor
public class UserMapper {

    private final AddressMapper addressMapper;
    private final IndividualMapper individualMapper;

    public UserEntity toUserEntity(CreateUserRequestDto request, CountryEntity country) {
        UserEntity user = new UserEntity();
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        if (request.getAddress() != null) {
            user.setAddress(addressMapper.toAddressEntity(request.getAddress(), country));
        }
        if (request.getIndividual() != null) {
            user.setIndividual(individualMapper.toIndividualEntity(request.getIndividual()));
        }
        return user;
    }

    public UserResponseDto toUserResponse(UserEntity user) {
        UserResponseDto response = new UserResponseDto()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .filled(user.isFilled())
                .createdAt(toUtc(user.getCreated()))
                .updatedAt(toUtc(user.getUpdated()));
        if (user.getAddress() != null) {
            response.address(addressMapper.toAddressResponse(user.getAddress()));
        }
        if (user.getIndividual() != null) {
            response.individual(individualMapper.toIndividualResponse(user.getIndividual()));
        }
        return response;
    }

    // PATCH: null means "not sent, leave as is", never "clear"
    public void updateUser(UserEntity user, UpdateUserRequestDto request, CountryEntity country) {
        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getAddress() != null) {
            addressMapper.updateAddress(user.getAddress(), request.getAddress(), country);
        }
        if (request.getIndividual() != null) {
            individualMapper.updateIndividual(user.getIndividual(), request.getIndividual());
        }
    }
}
