package com.dezxxx.transaction.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DifferentWalletsValidator implements ConstraintValidator<DifferentWallets, TransferWallets> {

    // a missing wallet is reported by @NotNull, not here
    @Override
    public boolean isValid(TransferWallets request, ConstraintValidatorContext context) {
        if (request == null || request.getWalletUid() == null || request.getTargetWalletUid() == null) {
            return true;
        }
        return !request.getWalletUid().equals(request.getTargetWalletUid());
    }
}
