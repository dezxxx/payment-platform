package com.dezxxx.individuals.error;

import com.dezxxx.individuals.api.model.ErrorResponseDto;
import com.dezxxx.individuals.logging.RequestLog;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
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

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ErrorResponseFactory errorResponseFactory;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponseDto> handleApiException(ApiException ex, ServerWebExchange exchange) {
        return respond(ex.getErrorCode(), ex.getMessage(), ex.getDetails(), exchange, ex);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(WebExchangeBindException ex,
                                                          ServerWebExchange exchange) {
        List<String> details = ex.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                details, exchange, ex);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponseDto> handleUnreadableInput(ServerWebInputException ex,
                                                               ServerWebExchange exchange) {
        return respond(ErrorCode.VALIDATION_ERROR, "Malformed request body", List.of(), exchange, ex);
    }

    // without this the catch-all would turn 404, 405 and 415 into 500
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponseDto> handleResponseStatus(ResponseStatusException ex,
                                                              ServerWebExchange exchange) {
        ErrorCode code = fromStatus(ex.getStatusCode());
        // ex.getHeaders() carries what HTTP requires with the status, e.g. Allow on a 405
        return respond(code, code.getDefaultMessage(), List.of(), exchange, ex, ex.getHeaders());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponseDto> handleAuthentication(AuthenticationException ex,
                                                              ServerWebExchange exchange) {
        return respond(ErrorCode.AUTHENTICATION_REQUIRED, ErrorCode.AUTHENTICATION_REQUIRED.getDefaultMessage(),
                List.of(), exchange, ex);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponseDto> handleAccessDenied(AccessDeniedException ex,
                                                            ServerWebExchange exchange) {
        return respond(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.getDefaultMessage(),
                List.of(), exchange, ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpected(Exception ex, ServerWebExchange exchange) {
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                List.of(), exchange, ex);
    }

    private ResponseEntity<ErrorResponseDto> respond(ErrorCode code,
                                                  String message,
                                                  List<String> details,
                                                  ServerWebExchange exchange,
                                                  Exception ex) {
        return respond(code, message, details, exchange, ex, HttpHeaders.EMPTY);
    }

    private ResponseEntity<ErrorResponseDto> respond(ErrorCode code,
                                                  String message,
                                                  List<String> details,
                                                  ServerWebExchange exchange,
                                                  Exception ex,
                                                  HttpHeaders headers) {
        HttpStatus status = code.getStatus();
        String path = exchange.getRequest().getPath().value();
        ErrorResponseDto body = errorResponseFactory.create(code, message, details, path);

        // before the log line, so that line already carries the code as a field
        RequestLog.errorCode(code.name());

        if (status.is5xxServerError()) {
            log.error("{} {} -> {} {}", exchange.getRequest().getMethod(), path, status.value(), code, ex);
        } else {
            log.warn("{} {} -> {} {}: {}", exchange.getRequest().getMethod(), path, status.value(), code, message);
        }
        return ResponseEntity.status(status).headers(headers).body(body);
    }

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
