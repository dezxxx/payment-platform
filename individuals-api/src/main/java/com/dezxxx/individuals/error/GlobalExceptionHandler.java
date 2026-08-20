package com.dezxxx.individuals.error;

import com.dezxxx.individuals.api.model.ErrorResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.bind.support.WebExchangeBindException;

/**
 * Turns anything a handler raises into the contract's {@link ErrorResponse}.
 *
 * <p>Reactive stack: the request is a {@link ServerWebExchange}, not a servlet
 * request. Returning a plain {@code ResponseEntity} is allowed - WebFlux wraps
 * it for us, and nothing here does blocking work that would need a Mono of its
 * own.
 *
 * <p>Failures from the filter chain do not arrive here. WebFlux offers no
 * {@code handlerExceptionResolver} to delegate them into an advice, so
 * {@link ApiAuthenticationEntryPoint} and {@link ApiAccessDeniedHandler} write
 * their answers directly, sharing {@link ErrorResponseFactory} with this class.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ErrorResponseFactory errorResponseFactory;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, ServerWebExchange exchange) {
        return respond(ex.getErrorCode(), ex.getMessage(), ex.getDetails(), exchange, ex);
    }

    /**
     * Raised when a request body fails {@code @Valid}. The reactive counterpart
     * of MethodArgumentNotValidException, and it carries the same BindingResult.
     * The offending fields go into {@code details}, one line each, as the
     * contract example shows.
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException ex,
                                                          ServerWebExchange exchange) {
        List<String> details = ex.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                details, exchange, ex);
    }

    /**
     * Malformed or empty body, or a missing query parameter. The message of this
     * exception names Java classes and stream positions, so it is logged rather
     * than returned.
     */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableInput(ServerWebInputException ex,
                                                               ServerWebExchange exchange) {
        return respond(ErrorCode.VALIDATION_ERROR, "Malformed request body", List.of(), exchange, ex);
    }

    /**
     * Everything the dispatcher rejects before a handler is chosen - unknown
     * path, wrong method, unsupported content type - reaches us as this one
     * type, each instance already carrying its correct status. Without this the
     * catch-all below would flatten all of them into 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex,
                                                              ServerWebExchange exchange) {
        ErrorCode code = fromStatus(ex.getStatusCode());
        return respond(code, code.getDefaultMessage(), List.of(), exchange, ex);
    }

    /**
     * Reached only when a handler itself rejects the caller, for example through
     * method security. The same failure raised in the filter chain is answered
     * by {@link ApiAuthenticationEntryPoint} instead.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                              ServerWebExchange exchange) {
        return respond(ErrorCode.INVALID_CREDENTIALS, ErrorCode.INVALID_CREDENTIALS.getDefaultMessage(),
                List.of(), exchange, ex);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                            ServerWebExchange exchange) {
        return respond(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.getDefaultMessage(),
                List.of(), exchange, ex);
    }

    /**
     * Anything unforeseen. The client gets a bare message and the trace id;
     * the stack trace stays in the log, where the same trace id leads to it.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, ServerWebExchange exchange) {
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                List.of(), exchange, ex);
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code,
                                                  String message,
                                                  List<String> details,
                                                  ServerWebExchange exchange,
                                                  Exception ex) {
        HttpStatus status = code.getStatus();
        String path = exchange.getRequest().getPath().value();
        ErrorResponse body = errorResponseFactory.create(code, message, details, path);

        if (status.is5xxServerError()) {
            log.error("{} {} -> {} {}", exchange.getRequest().getMethod(), path, status.value(), code, ex);
        } else {
            log.warn("{} {} -> {} {}: {}", exchange.getRequest().getMethod(), path, status.value(), code, message);
        }
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Only the statuses the framework can produce on its own are named. A 4xx
     * we did not foresee is still the caller's problem, so it is reported as a
     * validation failure rather than as our own error.
     */
    private static ErrorCode fromStatus(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        return status.is4xxClientError() ? ErrorCode.VALIDATION_ERROR : ErrorCode.INTERNAL_ERROR;
    }
}
