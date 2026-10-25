package com.dezxxx.transaction.mapper;

import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.WalletResponseDto;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.entity.wallet.WalletTypeEntity;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WalletMapper {

    public WalletEntity toWalletEntity(CreateWalletRequestDto request, WalletTypeEntity walletType, UUID userUid) {
        WalletEntity wallet = new WalletEntity();
        wallet.setName(request.getName());
        wallet.setWalletType(walletType);
        wallet.setUserUid(userUid);
        return wallet;
    }

    public WalletResponseDto toWalletResponse(WalletEntity wallet) {
        return new WalletResponseDto()
                .uid(wallet.getUid())
                .name(wallet.getName())
                .walletTypeUid(wallet.getWalletType().getUid())
                .currencyCode(wallet.getWalletType().getCurrencyCode())
                .status(wallet.getStatus().name())
                .balance(wallet.getBalance());
    }
}
