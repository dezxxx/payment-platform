package com.dezxxx.transaction.unit.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.GlobalExceptionHandler;
import com.dezxxx.transaction.exception.TransactionServiceException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private static final String PATH = "/api/v1/transactions/deposit/confirm";

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final Tracer tracer = mock(Tracer.class);

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(tracer);

    private final WebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", PATH));

    @BeforeEach
    void traceIsRunning() {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(TRACE_ID);
    }

    @Test
    @DisplayName("given a TransactionServiceException, when handled, then its ErrorCode decides status, error and type")
    void handlesServiceException() {
        // given
        TransactionServiceException ex = new TransactionServiceException(ErrorCode.REQUEST_ID_CONFLICT);

        // when
        ProblemDetail problem = handler.handleService(ex, request).getBody();

        // then
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getDetail()).isEqualTo("requestId already used for another operation");
        assertThat(problem.getType()).isEqualTo(URI.create("https://example.org/problems/request-id-conflict"));
        assertThat(problem.getInstance()).isEqualTo(URI.create(PATH));
        assertThat(problem.getProperties())
                .containsEntry("error", "REQUEST_ID_CONFLICT")
                .containsEntry("traceId", TRACE_ID)
                .containsEntry("details", List.of())
                .containsKey("timestamp");
    }

    @Test
    @DisplayName("given no current span, when handled, then traceId is 'unavailable' rather than missing")
    void traceIdWithoutSpan() {
        // given
        when(tracer.currentSpan()).thenReturn(null);

        // when
        ProblemDetail problem = handler.handleService(new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND), request)
                .getBody();

        // then
        assertThat(problem.getProperties()).containsEntry("traceId", "unavailable");
    }

    @Test
    @DisplayName("given the transaction names a wallet missing from the shard, when handled, then 404 WALLET_NOT_FOUND")
    void handlesWalletForeignKey() {
        // given
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("insert or update on table \"transactions\" violates foreign key constraint"
                        + " \"transactions_wallet_uid_fkey\""));

        // when
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getProperties()).containsEntry("error", "WALLET_NOT_FOUND");
    }

    @Test
    @DisplayName("given another constraint is violated, when handled, then 500 without the database text")
    void handlesOtherIntegrityViolation() {
        // given
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("new row for relation \"wallets\" violates check constraint"
                        + " \"ck_wallets_balance_not_negative\""));

        // when
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("Unexpected error");
        assertThat(response.getBody().getProperties()).containsEntry("error", "INTERNAL_ERROR");
    }

    @Test
    @DisplayName("given a bad request parameter, when handled, then 400 VALIDATION_ERROR names the parameter")
    void handlesConstraintViolation() {
        // given
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("getWalletsByUserUid.userUid");
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be null");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        // when
        ResponseEntity<ProblemDetail> response = handler.handleConstraintViolation(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties())
                .containsEntry("error", "VALIDATION_ERROR")
                .containsEntry("details", List.of("userUid: must not be null"));
    }

    @Test
    @DisplayName("given any other exception, when handled, then 500 and the exception text stays out of the answer")
    void handlesUnexpected() {
        // given
        IllegalStateException ex = new IllegalStateException("connection to 10.0.0.5 refused");

        // when
        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("Unexpected error");
    }

    @Test
    @DisplayName("given a body that is not JSON, when Spring reports it, then 400 MALFORMED_REQUEST")
    void handlesUnreadableBody() throws Exception {
        // given
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error",
                new MockHttpInputMessage(new byte[0]));

        // when
        ResponseEntity<Object> response = handler.handleException(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response)).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    @DisplayName("given no X-User-Uid header, when Spring reports it, then 400 VALIDATION_ERROR says it is required")
    void handlesMissingHeader() throws Exception {
        // given
        MethodParameter parameter = mock(MethodParameter.class);
        MissingRequestHeaderException ex = new MissingRequestHeaderException("X-User-Uid", parameter);

        // when
        ResponseEntity<Object> response = handler.handleException(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(((ProblemDetail) response.getBody()).getProperties())
                .containsEntry("details", List.of("X-User-Uid: is required"));
    }

    @Test
    @DisplayName("given an unsupported method, when Spring reports it, then 405 METHOD_NOT_ALLOWED with the Allow header kept")
    void handlesMethodNotAllowed() throws Exception {
        // when
        ResponseEntity<Object> response = handler.handleException(
                new HttpRequestMethodNotSupportedException("PUT", List.of("POST")), request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(errorOf(response)).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.POST);
    }

    @Test
    @DisplayName("given an unsupported content type, when Spring reports it, then 415 UNSUPPORTED_MEDIA_TYPE")
    void handlesUnsupportedMediaType() throws Exception {
        // when
        ResponseEntity<Object> response = handler.handleException(new HttpMediaTypeNotSupportedException("text/plain"), request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(errorOf(response)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("given a framework status we have no code for, when handled, then Spring's status stays and error names it")
    void handlesStatusWithoutCode() throws Exception {
        // when
        ResponseEntity<Object> response = handler.handleException(new HttpMediaTypeNotAcceptableException("application/xml"), request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(errorOf(response)).isEqualTo("NOT_ACCEPTABLE");
        assertThat(((ProblemDetail) response.getBody()).getProperties()).containsEntry("traceId", TRACE_ID);
    }

    private static Object errorOf(ResponseEntity<Object> response) {
        return ((ProblemDetail) response.getBody()).getProperties().get("error");
    }
}
