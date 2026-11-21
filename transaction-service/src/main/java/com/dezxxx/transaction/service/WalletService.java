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
import java.time.Clock;
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
    private final Clock clock;

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

    public WalletEntity getActiveWallets(UUID walletUid, UUID targetWalletUid, UUID userUid, String sourceCode,
                                         String destinationCode) {
        List<WalletEntity> wallets = requireTransferPair(
                walletRepository.findAllActiveByUids(List.of(walletUid, targetWalletUid), userUid));
        WalletEntity source = walletOf(wallets, walletUid);
        requireCodesMatchCurrencies(currencyOf(source), currencyOf(walletOf(wallets, targetWalletUid)), sourceCode,
                destinationCode);
        return source;
    }

    // confirm takes a DB row lock on both wallets (FOR NO KEY UPDATE) until its transaction ends;
    // currencies are checked at init only: they are signed into the offer token, and a wallet never changes its type
    @Transactional
    public WalletEntity lockActiveWallets(UUID walletUid, UUID targetWalletUid, UUID userUid) {
        List<WalletEntity> wallets = requireTransferPair(
                walletRepository.findAllActiveForUpdate(List.of(walletUid, targetWalletUid), userUid));
        return walletOf(wallets, walletUid);
    }

    @Transactional
    public void credit(UUID walletUid, UUID userUid, BigDecimal amount) {
        requireUpdated(walletRepository.credit(walletUid, userUid, amount, Instant.now(clock)));
        log.info("Credited {} to wallet {} of user {}", amount, walletUid, userUid);
    }

    @Transactional
    public void debit(UUID walletUid, UUID userUid, BigDecimal amount) {
        requireUpdated(walletRepository.debit(walletUid, userUid, amount, Instant.now(clock)));
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

    private static List<WalletEntity> requireTransferPair(List<WalletEntity> wallets) {
        if (wallets.size() < 2) {
            throw new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND);
        }
        wallets.forEach(WalletService::requireActive);
        return wallets;
    }

    // one currency: the request carries no codes; two currencies: the codes are the wallets' real currencies
    private static void requireCodesMatchCurrencies(String sourceCurrency, String targetCurrency, String sourceCode,
                                                    String destinationCode) {
        boolean matches = sourceCurrency.equals(targetCurrency)
                ? sourceCode == null && destinationCode == null
                : sourceCurrency.equals(sourceCode) && targetCurrency.equals(destinationCode);
        if (!matches) {
            throw new TransactionServiceException(ErrorCode.CURRENCY_MISMATCH);
        }
    }

    private static WalletEntity walletOf(List<WalletEntity> wallets, UUID walletUid) {
        return wallets.stream()
                .filter(wallet -> wallet.getUid().equals(walletUid))
                .findFirst()
                .orElseThrow();
    }

    private static String currencyOf(WalletEntity wallet) {
        return wallet.getWalletType().getCurrencyCode();
    }

    private WalletEntity newWallet(CreateWalletRequestDto request, WalletTypeEntity walletType, UUID userUid) {
        WalletEntity wallet = walletMapper.toWalletEntity(request, walletType, userUid);
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setBalance(BigDecimal.ZERO);
        return wallet;
    }

    private static void requireUpdated(int updatedRows) {
        if (updatedRows == 0) {
            throw new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND);
        }
    }
}
