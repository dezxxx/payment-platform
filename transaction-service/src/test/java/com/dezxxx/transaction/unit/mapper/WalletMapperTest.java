package com.dezxxx.transaction.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.WalletResponseDto;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.entity.wallet.WalletStatus;
import com.dezxxx.transaction.entity.wallet.WalletTypeEntity;
import com.dezxxx.transaction.mapper.WalletMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WalletMapper")
class WalletMapperTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID WALLET_UID = UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c");

    private static final UUID TYPE_UID = UUID.fromString("00000000-0000-0000-0000-000000000840");

    private final WalletMapper walletMapper = new WalletMapper();

    @Test
    @DisplayName("given a create request, when mapping to an entity, then name, type and owner are set")
    void mapsRequestToEntity() {
        // given
        CreateWalletRequestDto request = new CreateWalletRequestDto().name("Main USD").walletTypeUid(TYPE_UID);
        WalletTypeEntity type = walletType();

        // when
        WalletEntity wallet = walletMapper.toWalletEntity(request, type, USER_UID);

        // then
        assertThat(wallet.getName()).isEqualTo("Main USD");
        assertThat(wallet.getWalletType()).isSameAs(type);
        assertThat(wallet.getUserUid()).isEqualTo(USER_UID);
    }

    @Test
    @DisplayName("given a wallet, when mapping to a response, then the currency comes from its type")
    void mapsEntityToResponse() {
        // given
        WalletEntity wallet = new WalletEntity();
        wallet.setUid(WALLET_UID);
        wallet.setName("Main USD");
        wallet.setWalletType(walletType());
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setBalance(new BigDecimal("150.00"));

        // when
        WalletResponseDto response = walletMapper.toWalletResponse(wallet);

        // then
        assertThat(response.getUid()).isEqualTo(WALLET_UID);
        assertThat(response.getName()).isEqualTo("Main USD");
        assertThat(response.getWalletTypeUid()).isEqualTo(TYPE_UID);
        assertThat(response.getCurrencyCode()).isEqualTo("USD");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getBalance()).isEqualByComparingTo("150.00");
    }

    private static WalletTypeEntity walletType() {
        WalletTypeEntity type = new WalletTypeEntity();
        type.setUid(TYPE_UID);
        type.setCurrencyCode("USD");
        return type;
    }
}
