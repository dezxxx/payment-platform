package com.dezxxx.transaction.rest;

import com.dezxxx.transaction.api.WalletsApi;
import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.WalletResponseDto;
import com.dezxxx.transaction.service.WalletService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WalletRestControllerV1 implements WalletsApi {

    private final WalletService walletService;

    @Override
    public ResponseEntity<WalletResponseDto> createWallet(UUID xUserUid, CreateWalletRequestDto createWalletRequestDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(walletService.createWallet(xUserUid, createWalletRequestDto));
    }

    @Override
    public ResponseEntity<WalletResponseDto> getWalletById(UUID xUserUid, UUID walletUid) {
        return ResponseEntity.ok(walletService.getWalletById(xUserUid, walletUid));
    }

    @Override
    public ResponseEntity<List<WalletResponseDto>> getWalletsByUserUid(UUID userUid) {
        return ResponseEntity.ok(walletService.getWalletsByUserUid(userUid));
    }
}
