package com.dezxxx.person.mapper;

import com.dezxxx.person.api.model.CreateUserRequestAddressDto;
import com.dezxxx.person.api.model.UpdateUserRequestAddressDto;
import com.dezxxx.person.api.model.UserResponseAddressDto;
import com.dezxxx.person.entity.AddressEntity;
import com.dezxxx.person.entity.CountryEntity;
import org.springframework.stereotype.Component;

// The country arrives already found by the service: a mapper never queries the database
@Component
public class AddressMapper {

    public AddressEntity toAddressEntity(CreateUserRequestAddressDto request, CountryEntity country) {
        AddressEntity address = new AddressEntity();
        address.setCountry(country);
        address.setCity(request.getCity());
        address.setState(request.getState());
        address.setZipCode(request.getZipCode());
        address.setAddress(request.getAddressLine());
        return address;
    }

    public UserResponseAddressDto toAddressResponse(AddressEntity address) {
        UserResponseAddressDto response = new UserResponseAddressDto()
                .id(address.getId())
                .city(address.getCity())
                .state(address.getState())
                .zipCode(address.getZipCode())
                .addressLine(address.getAddress());
        if (address.getCountry() != null) {
            response.countryAlpha2(address.getCountry().getAlpha2())
                    .countryAlpha3(address.getCountry().getAlpha3());
        }
        return response;
    }

    // PATCH: null means "not sent, leave as is"
    public void updateAddress(AddressEntity address, UpdateUserRequestAddressDto request, CountryEntity country) {
        if (country != null) {
            address.setCountry(country);
        }
        if (request.getCity() != null) {
            address.setCity(request.getCity());
        }
        if (request.getState() != null) {
            address.setState(request.getState());
        }
        if (request.getZipCode() != null) {
            address.setZipCode(request.getZipCode());
        }
        if (request.getAddressLine() != null) {
            address.setAddress(request.getAddressLine());
        }
    }
}
