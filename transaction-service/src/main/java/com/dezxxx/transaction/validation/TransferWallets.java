package com.dezxxx.transaction.validation;

import java.util.UUID;

public interface TransferWallets {

    UUID getWalletUid();

    UUID getTargetWalletUid();
}
