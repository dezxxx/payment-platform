package com.dezxxx.transaction.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// a transfer must move money between two different wallets
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DifferentWalletsValidator.class)
public @interface DifferentWallets {

    String message() default "walletUid and targetWalletUid must be different wallets";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
