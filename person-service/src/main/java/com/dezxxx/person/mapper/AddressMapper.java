package com.dezxxx.person.mapper;

import com.dezxxx.person.api.model.CreateIndividualRequestUserAddressDto;
import com.dezxxx.person.api.model.IndividualResponseUserAddressDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestUserAddressDto;
import com.dezxxx.person.entity.AddressEntity;
import com.dezxxx.person.entity.CountryEntity;
import org.springframework.stereotype.Component;

@Component
public class AddressMapper {

    public AddressEntity toAddressEntity(CreateIndividualRequestUserAddressDto request, CountryEntity country) {
        AddressEntity address = new AddressEntity();
        address.setCountry(country);
        address.setCity(request.getCity());
        address.setState(request.getState());
        address.setZipCode(request.getZipCode());
        address.setAddress(request.getAddressLine());
        return address;
    }

    public IndividualResponseUserAddressDto toAddressResponse(AddressEntity address) {
        return new IndividualResponseUserAddressDto()
                .id(address.getId())
                .countryAlpha3(address.getCountry().getAlpha3())
                .countryAlpha2(address.getCountry().getAlpha2())
                .city(address.getCity())
                .state(address.getState())
                .zipCode(address.getZipCode())
                .addressLine(address.getAddress());
    }

    public void updateAddress(AddressEntity address, UpdateIndividualRequestUserAddressDto request, CountryEntity country) {
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
