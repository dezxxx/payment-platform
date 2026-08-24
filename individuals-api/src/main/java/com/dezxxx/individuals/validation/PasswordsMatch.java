package com.dezxxx.individuals.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The password and its confirmation must be the same string.
 *
 * <p>The one registration rule OpenAPI cannot express: its keywords describe a
 * field on its own - type, length, format - and never one field against
 * another. Everything else on {@code RegistrationRequest} is generated from the
 * contract; this is what is left over.
 *
 * <p>Placed on the type, not on a field, because the check needs both values at
 * once. A field constraint is handed a single value and could not see the other
 * one.
 *
 * <p>Not written on the class by hand - the class is generated. The contract
 * carries {@code x-class-extra-annotation} on the schema and the generator
 * stamps this annotation on, so the rule stays in the contract and is applied
 * by the same {@code @Valid} that runs everything else.
 *
 * <p>The default message reads as the second half of a sentence: the exception
 * handler prints it as {@code "confirmPassword: must match password"}, which is
 * the line the contract's <b>400 (Bad Request)</b> example promises.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordsMatchValidator.class)
public @interface PasswordsMatch {

    String message() default "must match password";

    /** Required by the Bean Validation spec; unused - we validate in one group. */
    Class<?>[] groups() default {};

    /** Required by the Bean Validation spec; unused - nothing reads metadata off this rule. */
    Class<? extends Payload>[] payload() default {};
}
