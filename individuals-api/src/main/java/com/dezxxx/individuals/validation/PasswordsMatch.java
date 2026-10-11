package com.dezxxx.individuals.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// OpenAPI checks fields one by one and cannot compare two; the contract puts this
// on RegistrationRequest via x-class-extra-annotation
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordsMatchValidator.class)
public @interface PasswordsMatch {

    String message() default "must match password";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
