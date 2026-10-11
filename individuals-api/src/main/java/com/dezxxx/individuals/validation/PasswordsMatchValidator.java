package com.dezxxx.individuals.validation;

import com.dezxxx.individuals.api.model.RegistrationRequestDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordsMatchValidator implements ConstraintValidator<PasswordsMatch, RegistrationRequestDto> {

    private static final String CONFIRMATION_FIELD = "confirmPassword";

    @Override
    public boolean isValid(RegistrationRequestDto request, ConstraintValidatorContext context) {
        if (request == null || request.getPassword() == null || request.getConfirmPassword() == null) {
            return true;
        }
        if (request.getPassword().equals(request.getConfirmPassword())) {
            return true;
        }
        reportOnConfirmation(context);
        return false;
    }

    // details are built from field errors only, so the error moves onto confirmPassword
    private static void reportOnConfirmation(ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(CONFIRMATION_FIELD)
                .addConstraintViolation();
    }
}
