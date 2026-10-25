package com.dezxxx.transaction.rest;

import com.dezxxx.transaction.api.TransactionsApi;
import com.dezxxx.transaction.api.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.api.model.DepositInitRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.service.TransactionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TransactionRestControllerV1 implements TransactionsApi {

    private final TransactionService transactionService;

    @Override
    public ResponseEntity<TransactionInitResponseDto> initDeposit(UUID xUserUid, DepositInitRequestDto depositInitRequestDto) {
        return ResponseEntity.ok(transactionService.initDeposit(xUserUid, depositInitRequestDto));
    }

    @Override
    public ResponseEntity<TransactionConfirmResponseDto> confirmDeposit(UUID xUserUid,
                                                                        DepositConfirmRequestDto depositConfirmRequestDto) {
        return ResponseEntity.ok(transactionService.confirmDeposit(xUserUid, depositConfirmRequestDto));
    }
}
