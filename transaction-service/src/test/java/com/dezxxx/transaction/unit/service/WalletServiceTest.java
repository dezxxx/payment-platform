package com.dezxxx.transaction.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.WalletResponseDto;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.entity.wallet.WalletStatus;
import com.dezxxx.transaction.entity.wallet.WalletTypeEntity;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.mapper.WalletMapper;
import com.dezxxx.transaction.repository.WalletRepository;
import com.dezxxx.transaction.repository.WalletTypeRepository;
import com.dezxxx.transaction.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("WalletService")
class WalletServiceTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID WALLET_UID = UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c");

    private static final UUID TYPE_UID = UUID.fromString("00000000-0000-0000-0000-000000000840");

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletTypeRepository walletTypeRepository;

    @Mock
    private WalletMapper walletMapper;

    @InjectMocks
    private WalletService walletService;

    @Test
    @DisplayName("given an active wallet type, when creating, then the wallet is ACTIVE with balance 0 and saved at once")
    void createsWallet() {
        // given
        CreateWalletRequestDto request = createRequest();
        WalletTypeEntity type = walletType("ACTIVE", null);
        WalletEntity wallet = new WalletEntity();
        WalletResponseDto response = new WalletResponseDto().uid(WALLET_UID);
        when(walletTypeRepository.findById(TYPE_UID)).thenReturn(Optional.of(type));
        when(walletMapper.toWalletEntity(request, type, USER_UID)).thenReturn(wallet);
        when(walletRepository.saveAndFlush(wallet)).thenReturn(wallet);
        when(walletMapper.toWalletResponse(wallet)).thenReturn(response);

        // when
        WalletResponseDto result = walletService.createWallet(USER_UID, request);

        // then
        assertThat(result).isSameAs(response);
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(walletRepository).saveAndFlush(wallet);
    }

    @Test
    @DisplayName("given an unknown wallet type, when creating, then WALLET_TYPE_NOT_FOUND and nothing is saved")
    void refusesUnknownType() {
        // given
        when(walletTypeRepository.findById(TYPE_UID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> walletService.createWallet(USER_UID, createRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_TYPE_NOT_FOUND);
        verify(walletRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("given an archived wallet type, when creating, then WALLET_TYPE_NOT_ACTIVE and nothing is saved")
    void refusesArchivedType() {
        // given
        when(walletTypeRepository.findById(TYPE_UID)).thenReturn(Optional.of(walletType("ACTIVE", Instant.now())));

        // when / then
        assertThatThrownBy(() -> walletService.createWallet(USER_UID, createRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_TYPE_NOT_ACTIVE);
        verify(walletRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("given a wallet type not ACTIVE, when creating, then WALLET_TYPE_NOT_ACTIVE and nothing is saved")
    void refusesInactiveType() {
        // given
        when(walletTypeRepository.findById(TYPE_UID)).thenReturn(Optional.of(walletType("BLOCKED", null)));

        // when / then
        assertThatThrownBy(() -> walletService.createWallet(USER_UID, createRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_TYPE_NOT_ACTIVE);
        verify(walletRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("given the user's active wallet, when getting it, then it is returned")
    void getsWallet() {
        // given
        WalletEntity wallet = new WalletEntity();
        WalletResponseDto response = new WalletResponseDto().uid(WALLET_UID);
        when(walletRepository.findActive(WALLET_UID, USER_UID)).thenReturn(Optional.of(wallet));
        when(walletMapper.toWalletResponse(wallet)).thenReturn(response);

        // when
        WalletResponseDto result = walletService.getWalletById(USER_UID, WALLET_UID);

        // then
        assertThat(result).isSameAs(response);
    }

    @Test
    @DisplayName("given no active wallet of this user, when getting it, then WALLET_NOT_FOUND")
    void refusesMissingWallet() {
        // given
        when(walletRepository.findActive(WALLET_UID, USER_UID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> walletService.getWalletById(USER_UID, WALLET_UID))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("given the user's ACTIVE wallet, when taking it for an operation, then the entity is returned")
    void givesWalletForOperation() {
        // given
        WalletEntity wallet = new WalletEntity();
        wallet.setStatus(WalletStatus.ACTIVE);
        when(walletRepository.findActive(WALLET_UID, USER_UID)).thenReturn(Optional.of(wallet));

        // when
        WalletEntity result = walletService.getWalletForOperation(WALLET_UID, USER_UID);

        // then
        assertThat(result).isSameAs(wallet);
    }

    @Test
    @DisplayName("given no active wallet of this user, when taking it for an operation, then WALLET_NOT_FOUND")
    void refusesMissingWalletForOperation() {
        // given
        when(walletRepository.findActive(WALLET_UID, USER_UID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> walletService.getWalletForOperation(WALLET_UID, USER_UID))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("given a user with two wallets, when listing, then both are returned in order")
    void listsWallets() {
        // given
        WalletEntity first = new WalletEntity();
        WalletEntity second = new WalletEntity();
        WalletResponseDto firstResponse = new WalletResponseDto().name("first");
        WalletResponseDto secondResponse = new WalletResponseDto().name("second");
        when(walletRepository.findAllActive(USER_UID)).thenReturn(List.of(first, second));
        when(walletMapper.toWalletResponse(first)).thenReturn(firstResponse);
        when(walletMapper.toWalletResponse(second)).thenReturn(secondResponse);

        // when
        List<WalletResponseDto> result = walletService.getWalletsByUserUid(USER_UID);

        // then
        assertThat(result).containsExactly(firstResponse, secondResponse);
    }

    @Test
    @DisplayName("given a user without wallets, when listing, then the list is empty")
    void listsNothing() {
        // given
        when(walletRepository.findAllActive(USER_UID)).thenReturn(List.of());

        // when
        List<WalletResponseDto> result = walletService.getWalletsByUserUid(USER_UID);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("given the user's wallet, when credit, then the balance grows in the database")
    void credits() {
        // given
        BigDecimal amount = new BigDecimal("100.00");
        when(walletRepository.credit(eq(WALLET_UID), eq(USER_UID), eq(amount), any())).thenReturn(1);

        // when
        walletService.credit(WALLET_UID, USER_UID, amount);

        // then
        verify(walletRepository).credit(eq(WALLET_UID), eq(USER_UID), eq(amount), any());
    }

    @Test
    @DisplayName("given no such wallet in the user's shard, when credit, then WALLET_NOT_FOUND and the caller rolls back")
    void creditRefusesUnknownWallet() {
        // given
        when(walletRepository.credit(eq(WALLET_UID), eq(USER_UID), any(), any())).thenReturn(0);

        // when / then
        assertThatThrownBy(() -> walletService.credit(WALLET_UID, USER_UID, BigDecimal.TEN))
                .isInstanceOfSatisfying(TransactionServiceException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WALLET_NOT_FOUND));
    }

    private static CreateWalletRequestDto createRequest() {
        return new CreateWalletRequestDto().name("Main USD").walletTypeUid(TYPE_UID);
    }

    private static WalletTypeEntity walletType(String status, Instant archivedAt) {
        WalletTypeEntity type = new WalletTypeEntity();
        type.setUid(TYPE_UID);
        type.setCurrencyCode("USD");
        type.setStatus(status);
        type.setArchivedAt(archivedAt);
        return type;
    }
}
