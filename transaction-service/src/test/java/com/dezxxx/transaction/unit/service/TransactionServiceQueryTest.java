package com.dezxxx.transaction.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.api.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.config.FeeProperties;
import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.repository.TransactionEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import com.dezxxx.transaction.service.TransactionService;
import com.dezxxx.transaction.service.WalletService;
import com.dezxxx.transaction.validation.DateRangeValidator;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService: status and search")
class TransactionServiceQueryTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID TRANSACTION_UID = UUID.fromString("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    private static final OffsetDateTime EARLY = OffsetDateTime.parse("2026-10-01T00:00:00Z");

    private static final OffsetDateTime LATE = OffsetDateTime.parse("2026-10-31T00:00:00Z");

    private static final FeeProperties FEES =
            new FeeProperties(BigDecimal.ZERO, new BigDecimal("0.01"), new BigDecimal("0.005"));

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionEventRepository transactionEventRepository;

    @Mock
    private WalletService walletService;

    @Mock
    private TransactionMapper transactionMapper;

    @Mock
    private DateRangeValidator dateRangeValidator;

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, transactionEventRepository, walletService,
                FEES, transactionMapper, dateRangeValidator, tracer, propagator);
    }

    @Test
    @DisplayName("given the user's transaction, when its status is read, then it is mapped to the response")
    void getsStatus() {
        // given
        TransactionEntity transaction = new TransactionEntity();
        TransactionStatusResponseDto response = new TransactionStatusResponseDto();
        when(transactionRepository.findByUidAndUserUid(TRANSACTION_UID, USER_UID)).thenReturn(Optional.of(transaction));
        when(transactionMapper.toStatusResponse(transaction)).thenReturn(response);

        // when
        TransactionStatusResponseDto result = transactionService.getTransactionStatus(USER_UID, TRANSACTION_UID);

        // then
        assertThat(result).isSameAs(response);
    }

    @Test
    @DisplayName("given no such transaction of this user, when its status is read, then TRANSACTION_NOT_FOUND")
    void refusesUnknownTransaction() {
        // given
        when(transactionRepository.findByUidAndUserUid(TRANSACTION_UID, USER_UID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> transactionService.getTransactionStatus(USER_UID, TRANSACTION_UID))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TRANSACTION_NOT_FOUND);
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("given found transactions, when searching, then each is mapped in the order found")
    @SuppressWarnings("unchecked")
    void searches() {
        // given
        TransactionEntity newer = new TransactionEntity();
        TransactionEntity older = new TransactionEntity();
        TransactionStatusResponseDto newerResponse = new TransactionStatusResponseDto();
        TransactionStatusResponseDto olderResponse = new TransactionStatusResponseDto();
        when(transactionRepository.findBy(any(Specification.class), any()))
                .thenReturn(new SliceImpl<>(List.of(newer, older)));
        when(transactionMapper.toStatusResponse(newer)).thenReturn(newerResponse);
        when(transactionMapper.toStatusResponse(older)).thenReturn(olderResponse);

        // when
        List<TransactionStatusResponseDto> result = transactionService.searchTransactions(USER_UID, null, null, null,
                EARLY, LATE, 0, 20);

        // then
        assertThat(result).containsExactly(newerResponse, olderResponse);
    }

    @Test
    @DisplayName("given dateFrom after dateTo, when searching, then VALIDATION_ERROR before the database is asked")
    void refusesReversedDatesFirst() {
        // given
        doThrow(new TransactionServiceException(ErrorCode.VALIDATION_ERROR, "dateFrom must not be after dateTo"))
                .when(dateRangeValidator).requireOrdered(LATE, EARLY);

        // when / then
        assertThatThrownBy(() -> transactionService.searchTransactions(USER_UID, null, null, null, LATE, EARLY, 0, 20))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(transactionRepository);
    }
}
