package com.dezxxx.individuals.validation;

import com.dezxxx.individuals.api.model.RegistrationRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Checks {@link PasswordsMatch} on a registration request.
 *
 * <p>Nothing registers this class. The annotation names it in
 * {@code @Constraint(validatedBy = ...)}, and Hibernate Validator asks Spring's
 * factory for an instance - so it is neither a {@code @Component} nor a
 * {@code @Bean}, and would still get its dependencies injected if it ever grew
 * any. It has none: the whole rule is two strings from the same request.
 *
 * <p>Typed against the generated {@code RegistrationRequest} rather than
 * reading the fields by name through reflection. A renamed field in the
 * contract then breaks the build instead of quietly disabling the rule at
 * runtime.
 */
public class PasswordsMatchValidator implements ConstraintValidator<PasswordsMatch, RegistrationRequest> {

    /**
     * The field the failure is reported on. The confirmation is what the user
     * mistyped - the password itself is not wrong, it simply has nothing to
     * agree with.
     */
    private static final String CONFIRMATION_FIELD = "confirmPassword";

    /**
     * A missing value is somebody else's failure. Both fields are
     * {@code @NotNull} already, and reporting the same omission twice would put
     * two lines in {@code details} for one mistake.
     *
     * <p>A plain {@code equals}: both values arrive in the same request and
     * neither is a stored secret, so there is nothing here for a timing
     * comparison to protect.
     */
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

    /**
     * Moves the failure onto a field.
     *
     * <p>A constraint on a type produces a violation that belongs to the whole
     * object and carries no field name, and the exception handler builds
     * {@code details} out of field errors only - so left as it is, the rule
     * would answer <b>400 (Bad Request)</b> with an empty {@code details} and
     * never say what was wrong. Rebuilding the violation on
     * {@code confirmPassword} puts it back among the field errors, next to the
     * ones the generated annotations produce.
     */
    private static void reportOnConfirmation(ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(CONFIRMATION_FIELD)
                .addConstraintViolation();
    }
}
