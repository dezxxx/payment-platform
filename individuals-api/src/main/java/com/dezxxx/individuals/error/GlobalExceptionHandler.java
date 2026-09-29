package com.dezxxx.individuals.error;

import com.dezxxx.individuals.api.model.ErrorResponse;
import com.dezxxx.individuals.logging.RequestLog;
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

// Turns any exception from a controller into our ErrorResponse.
// Filter-chain errors never get here - ApiAuthenticationEntryPoint and
// ApiAccessDeniedHandler answer those, sharing ErrorResponseFactory with us
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ErrorResponseFactory errorResponseFactory;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, ServerWebExchange exchange) {
        return respond(ex.getErrorCode(), ex.getMessage(), ex.getDetails(), exchange, ex);
    }

    // @Valid failed on the body; each bad field becomes one line in details
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException ex,
                                                          ServerWebExchange exchange) {
        List<String> details = ex.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                details, exchange, ex);
    }

    // broken or empty body. Its message names Java classes - logged, not returned
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableInput(ServerWebInputException ex,
                                                               ServerWebExchange exchange) {
        return respond(ErrorCode.VALIDATION_ERROR, "Malformed request body", List.of(), exchange, ex);
    }

    // unknown path, wrong method, wrong content type - each already has its
    // status; without this the catch-all would turn them all into 500
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex,
                                                              ServerWebExchange exchange) {
        ErrorCode code = fromStatus(ex.getStatusCode());
        return respond(code, code.getDefaultMessage(), List.of(), exchange, ex);
    }

    // only when a controller itself rejects the caller; the filter chain
    // case is answered by ApiAuthenticationEntryPoint
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                              ServerWebExchange exchange) {
        return respond(ErrorCode.AUTHENTICATION_REQUIRED, ErrorCode.AUTHENTICATION_REQUIRED.getDefaultMessage(),
                List.of(), exchange, ex);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                            ServerWebExchange exchange) {
        return respond(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.getDefaultMessage(),
                List.of(), exchange, ex);
    }

    // anything else: the client gets a plain message and the trace id,
    // the stack trace stays in the log
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

        // before the log line, so that line already carries the code as a field
        RequestLog.errorCode(code.name());

        if (status.is5xxServerError()) {
            log.error("{} {} -> {} {}", exchange.getRequest().getMethod(), path, status.value(), code, ex);
        } else {
            log.warn("{} {} -> {} {}: {}", exchange.getRequest().getMethod(), path, status.value(), code, message);
        }
        return ResponseEntity.status(status).body(body);
    }

    // statuses the framework can produce; any other 4xx is the caller's
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
