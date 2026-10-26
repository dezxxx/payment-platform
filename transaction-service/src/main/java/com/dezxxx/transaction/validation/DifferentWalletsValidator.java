package com.dezxxx.transaction.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DifferentWalletsValidator implements ConstraintValidator<DifferentWallets, TransferWallets> {

    private static final String TARGET_FIELD = "targetWalletUid";

    // a missing wallet is reported by @NotNull, not here
    @Override
    public boolean isValid(TransferWallets request, ConstraintValidatorContext context) {
        if (request == null || request.getWalletUid() == null || request.getTargetWalletUid() == null) {
            return true;
        }
        if (!request.getWalletUid().equals(request.getTargetWalletUid())) {
            return true;
        }
        reportOnTarget(context);
        return false;
    }

    // details are built from field errors only, so the error moves onto targetWalletUid
    private static void reportOnTarget(ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(TARGET_FIELD)
                .addConstraintViolation();
    }
}
