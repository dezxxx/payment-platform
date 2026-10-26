package com.dezxxx.transaction.rest;

import com.dezxxx.transaction.api.TransactionsApi;
import com.dezxxx.transaction.api.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.api.model.DepositInitRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.api.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.api.model.TransactionTypeDto;
import com.dezxxx.transaction.api.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.api.model.TransferInitRequestDto;
import com.dezxxx.transaction.api.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.api.model.WithdrawalInitRequestDto;
import com.dezxxx.transaction.service.TransactionService;
import java.time.OffsetDateTime;
import java.util.List;
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

    @Override
    public ResponseEntity<TransactionInitResponseDto> initWithdrawal(UUID xUserUid,
                                                                     WithdrawalInitRequestDto withdrawalInitRequestDto) {
        return ResponseEntity.ok(transactionService.initWithdrawal(xUserUid, withdrawalInitRequestDto));
    }

    @Override
    public ResponseEntity<TransactionConfirmResponseDto> confirmWithdrawal(UUID xUserUid,
                                                                           WithdrawalConfirmRequestDto withdrawalConfirmRequestDto) {
        return ResponseEntity.ok(transactionService.confirmWithdrawal(xUserUid, withdrawalConfirmRequestDto));
    }

    @Override
    public ResponseEntity<TransactionInitResponseDto> initTransfer(UUID xUserUid,
                                                                   TransferInitRequestDto transferInitRequestDto) {
        return ResponseEntity.ok(transactionService.initTransfer(xUserUid, transferInitRequestDto));
    }

    @Override
    public ResponseEntity<TransactionConfirmResponseDto> confirmTransfer(UUID xUserUid,
                                                                         TransferConfirmRequestDto transferConfirmRequestDto) {
        return ResponseEntity.ok(transactionService.confirmTransfer(xUserUid, transferConfirmRequestDto));
    }

    @Override
    public ResponseEntity<TransactionStatusResponseDto> getTransactionStatus(UUID xUserUid, UUID transactionId) {
        return ResponseEntity.ok(transactionService.getTransactionStatus(xUserUid, transactionId));
    }

    @Override
    public ResponseEntity<List<TransactionStatusResponseDto>> searchTransactions(UUID userUid, UUID walletUid,
                                                                                 TransactionTypeDto type,
                                                                                 TransactionStatusDto status,
                                                                                 OffsetDateTime dateFrom,
                                                                                 OffsetDateTime dateTo,
                                                                                 Integer page, Integer size) {
        return ResponseEntity.ok(transactionService.searchTransactions(userUid, walletUid, type, status, dateFrom,
                dateTo, page, size));
    }
}
