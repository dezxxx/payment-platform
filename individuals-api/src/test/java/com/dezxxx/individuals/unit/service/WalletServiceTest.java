package com.dezxxx.individuals.unit.service;

import static com.dezxxx.individuals.unit.service.ServiceTokens.USER_UID;
import static com.dezxxx.individuals.unit.service.ServiceTokens.accessToken;
import static com.dezxxx.individuals.unit.service.ServiceTokens.tokenWithoutUserUid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.transaction.TransactionClient;
import com.dezxxx.individuals.service.WalletService;
import com.dezxxx.transaction.client.model.CreateWalletRequestDto;
import com.dezxxx.transaction.client.model.WalletResponseDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("WalletService")
class WalletServiceTest {

    private static final UUID WALLET_UID = UUID.fromString("3b0e6a0c-5f4e-4d1a-9a57-1c2b3d4e5f60");

    private static final UUID USD_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000840");

    @Mock
    private TransactionClient transactionClient;

    @InjectMocks
    private WalletService walletService;

    @Test
    @DisplayName("given a token, when creating a wallet, then it is created for the token's user_uid")
    void createsForTokenUser() {
        // given
        CreateWalletRequestDto request = new CreateWalletRequestDto("Main USD", USD_TYPE);
        when(transactionClient.createWallet(USER_UID, request)).thenReturn(Mono.just(wallet()));

        // when / then
        StepVerifier.create(walletService.createWallet(accessToken(), request))
                .assertNext(wallet -> assertThat(wallet.getUid()).isEqualTo(WALLET_UID))
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token, when reading a wallet, then it is looked up among the token user's wallets")
    void readsForTokenUser() {
        // given
        when(transactionClient.getWalletById(USER_UID, WALLET_UID)).thenReturn(Mono.just(wallet()));

        // when / then
        StepVerifier.create(walletService.getWalletById(accessToken(), WALLET_UID))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token, when reading own wallets, then the token's user_uid names the user")
    void readsOwnWallets() {
        // given
        when(transactionClient.getWalletsByUserUid(USER_UID)).thenReturn(Mono.just(List.of(wallet())));

        // when / then
        StepVerifier.create(walletService.getMyWallets(accessToken()))
                .assertNext(wallets -> assertThat(wallets).hasSize(1))
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token without user_uid, when reading own wallets, then INTERNAL_ERROR and transaction-service is not called")
    void refusesTokenWithoutUserUid() {
        // when / then
        StepVerifier.create(walletService.getMyWallets(tokenWithoutUserUid()))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR))
                .verify();

        // then
        verifyNoInteractions(transactionClient);
    }

    private static WalletResponseDto wallet() {
        return new WalletResponseDto(WALLET_UID, "Main USD", USD_TYPE, "USD", "ACTIVE", BigDecimal.ZERO);
    }
}
