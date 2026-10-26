package com.dezxxx.transaction.validation;

import java.util.UUID;

// the two wallets of a transfer request, as @DifferentWallets reads them
public interface TransferWallets {

    UUID getWalletUid();

    UUID getTargetWalletUid();
}
