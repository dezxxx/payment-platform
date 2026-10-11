package com.dezxxx.person.mapper;

import static com.dezxxx.person.util.DateTimeUtil.toUtc;

import com.dezxxx.person.api.model.CreateIndividualRequestUserDto;
import com.dezxxx.person.api.model.IndividualResponseUserDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserDto;
import com.dezxxx.person.entity.CountryEntity;
import com.dezxxx.person.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserMapper {

    private final AddressMapper addressMapper;

    public UserEntity toUserEntity(CreateIndividualRequestUserDto request, CountryEntity country) {
        UserEntity user = new UserEntity();
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setAddress(addressMapper.toAddressEntity(request.getAddress(), country));
        return user;
    }

    public IndividualResponseUserDto toUserResponse(UserEntity user) {
        return new IndividualResponseUserDto()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .filled(user.isFilled())
                .createdAt(toUtc(user.getCreated()))
                .updatedAt(toUtc(user.getUpdated()))
                .address(addressMapper.toAddressResponse(user.getAddress()));
    }

    public void updateUser(UserEntity user, UpdateIndividualRequestUserDto request, CountryEntity country) {
        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getAddress() != null) {
            addressMapper.updateAddress(user.getAddress(), request.getAddress(), country);
        }
    }
}
