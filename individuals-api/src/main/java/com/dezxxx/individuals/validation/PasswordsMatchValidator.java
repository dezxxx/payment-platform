package com.dezxxx.individuals.validation;

import com.dezxxx.individuals.api.model.RegistrationRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// typed on the generated RegistrationRequest: a renamed field breaks the build
public class PasswordsMatchValidator implements ConstraintValidator<PasswordsMatch, RegistrationRequest> {

    private static final String CONFIRMATION_FIELD = "confirmPassword";

    // a missing value is @NotNull's job - do not report it twice
    @Override
    public boolean isValid(RegistrationRequest request, ConstraintValidatorContext context) {
        if (request == null || request.getPassword() == null || request.getConfirmPassword() == null) {
            return true;
        }
        if (request.getPassword().equals(request.getConfirmPassword())) {
            return true;
        }
        reportOnConfirmation(context);
        return false;
    }

    // a class-level error has no field, and details are built from field
    // errors only - so move it onto confirmPassword, or details stay empty
    private static void reportOnConfirmation(ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(CONFIRMATION_FIELD)
                .addConstraintViolation();
    }
}
