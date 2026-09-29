package com.dezxxx.individuals.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// password == confirmPassword. The one rule OpenAPI cannot express (it checks
// fields one by one), so it sits on the whole class. The generator puts it on
// RegistrationRequest via x-class-extra-annotation in the contract
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordsMatchValidator.class)
public @interface PasswordsMatch {

    String message() default "must match password";

    // required by Bean Validation, unused
    Class<?>[] groups() default {};

    // required by Bean Validation, unused
    Class<? extends Payload>[] payload() default {};
}
