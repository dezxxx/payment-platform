package com.dezxxx.transaction.service;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletService {

    private static final String OPERATIONS = "wallet.operations";

    private static final String ACTIVE_TYPE = "ACTIVE";

    private final WalletRepository walletRepository;
    private final WalletTypeRepository walletTypeRepository;
    private final WalletMapper walletMapper;

    @Transactional
    @Observed(name = OPERATIONS, contextualName = "walletService.create", lowCardinalityKeyValues = {"operation", "create"})
    public WalletResponseDto createWallet(UUID userUid, CreateWalletRequestDto request) {
        WalletTypeEntity walletType = findActiveType(request.getWalletTypeUid());
        WalletEntity wallet = newWallet(request, walletType, userUid);

        // flush now: a failed INSERT must answer 500 here, not after the response is built
        WalletEntity saved = walletRepository.saveAndFlush(wallet);
        log.info("Created wallet {} of user {}", saved.getUid(), userUid);
        return walletMapper.toWalletResponse(saved);
    }

    @Observed(name = OPERATIONS, contextualName = "walletService.getById", lowCardinalityKeyValues = {"operation", "getById"})
    public WalletResponseDto getWalletById(UUID userUid, UUID walletUid) {
        WalletEntity wallet = walletRepository.findActive(walletUid, userUid)
                .orElseThrow(() -> new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND));
        log.info("Found wallet {} of user {}", walletUid, userUid);
        return walletMapper.toWalletResponse(wallet);
    }

    public WalletEntity getActiveWallet(UUID walletUid, UUID userUid) {
        return requireFoundAndActive(walletRepository.findActive(walletUid, userUid));
    }

    @Transactional
    public WalletEntity lockActiveWallet(UUID walletUid, UUID userUid) {
        return requireFoundAndActive(walletRepository.findActiveForUpdate(walletUid, userUid));
    }

    public WalletEntity getActiveSameCurrencyWallet(UUID walletUid, UUID targetWalletUid, UUID userUid) {
        return requireTransferPair(
                walletRepository.findAllActiveByUids(List.of(walletUid, targetWalletUid), userUid), walletUid);
    }

    @Transactional
    public WalletEntity lockActiveSameCurrencyWallets(UUID walletUid, UUID targetWalletUid, UUID userUid) {
        return requireTransferPair(
                walletRepository.findAllActiveForUpdate(List.of(walletUid, targetWalletUid), userUid), walletUid);
    }

    @Transactional
    public void credit(UUID walletUid, UUID userUid, BigDecimal amount) {
        int updated = walletRepository.credit(walletUid, userUid, amount, Instant.now());
        if (updated == 0) {
            throw new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND);
        }
        log.info("Credited {} to wallet {} of user {}", amount, walletUid, userUid);
    }

    @Transactional
    public void debit(UUID walletUid, UUID userUid, BigDecimal amount) {
        int updated = walletRepository.debit(walletUid, userUid, amount, Instant.now());
        if (updated == 0) {
            throw new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND);
        }
        log.info("Debited {} from wallet {} of user {}", amount, walletUid, userUid);
    }

    @Observed(name = OPERATIONS, contextualName = "walletService.getByUser", lowCardinalityKeyValues = {"operation", "getByUser"})
    public List<WalletResponseDto> getWalletsByUserUid(UUID userUid) {
        List<WalletEntity> wallets = walletRepository.findAllActive(userUid);
        log.info("Found {} wallets of user {}", wallets.size(), userUid);
        return wallets.stream().map(walletMapper::toWalletResponse).toList();
    }

    private WalletTypeEntity findActiveType(UUID walletTypeUid) {
        WalletTypeEntity walletType = walletTypeRepository.findById(walletTypeUid)
                .orElseThrow(() -> new TransactionServiceException(ErrorCode.WALLET_TYPE_NOT_FOUND));
        if (walletType.getArchivedAt() != null || !ACTIVE_TYPE.equals(walletType.getStatus())) {
            throw new TransactionServiceException(ErrorCode.WALLET_TYPE_NOT_ACTIVE);
        }
        return walletType;
    }

    private WalletEntity requireFoundAndActive(Optional<WalletEntity> found) {
        return requireActive(found.orElseThrow(() -> new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND)));
    }

    private static WalletEntity requireActive(WalletEntity wallet) {
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new TransactionServiceException(ErrorCode.WALLET_NOT_ACTIVE);
        }
        return wallet;
    }

    private static WalletEntity requireTransferPair(List<WalletEntity> wallets, UUID sourceUid) {
        if (wallets.size() < 2) {
            throw new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND);
        }
        wallets.forEach(WalletService::requireActive);
        String currency = wallets.getFirst().getWalletType().getCurrencyCode();
        if (!currency.equals(wallets.getLast().getWalletType().getCurrencyCode())) {
            throw new TransactionServiceException(ErrorCode.CURRENCY_MISMATCH);
        }
        return wallets.stream()
                .filter(wallet -> wallet.getUid().equals(sourceUid))
                .findFirst()
                .orElseThrow();
    }

    private WalletEntity newWallet(CreateWalletRequestDto request, WalletTypeEntity walletType, UUID userUid) {
        WalletEntity wallet = walletMapper.toWalletEntity(request, walletType, userUid);
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setBalance(BigDecimal.ZERO);
        return wallet;
    }
}
