package com.dezxxx.transaction.exception;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String PROBLEM_TYPE_BASE = "https://example.org/problems/";

    private static final String NO_TRACE = "unavailable";

    private final Tracer tracer;

    @ExceptionHandler(TransactionServiceException.class)
    public ResponseEntity<ProblemDetail> handleService(TransactionServiceException ex, WebRequest request) {
        return respond(ex, ex.getErrorCode(), ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(violation -> lastNode(violation.getPropertyPath().toString()) + ": " + violation.getMessage())
                .sorted()
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return respond(ex, code, code.getDefaultMessage(), details, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, WebRequest request) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return respond(ex, code, code.getDefaultMessage(), List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = codeFor(ex, statusCode);
        if (code == null) {
            ProblemDetail problem = ProblemDetail.forStatus(statusCode);
            addPlatformFields(problem, HttpStatus.valueOf(statusCode.value()).name(), List.of());
            problem.setInstance(URI.create(path(request)));
            log.warn("{} -> {}: {}", path(request), statusCode.value(), ex.getMessage());
            return ResponseEntity.status(statusCode).headers(headers).body(problem);
        }
        // headers carry what HTTP requires with the status, e.g. Allow on a 405
        return ResponseEntity.status(code.getStatus())
                .headers(headers)
                .body(problem(ex, code, code.getDefaultMessage(), detailsOf(ex), request));
    }

    private ResponseEntity<ProblemDetail> respond(Exception ex, ErrorCode code, String detail,
                                                  List<String> details, WebRequest request) {
        return ResponseEntity.status(code.getStatus()).body(problem(ex, code, detail, details, request));
    }

    private ProblemDetail problem(Exception ex, ErrorCode code, String detail,
                                  List<String> details, WebRequest request) {
        String path = path(request);
        if (code.getStatus().is5xxServerError()) {
            log.error("{} -> {} {}", path, code.getStatus().value(), code, ex);
        } else {
            log.warn("{} -> {} {}: {}", path, code.getStatus().value(), code, detail);
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.getStatus(), detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create(path));
        addPlatformFields(problem, code.name(), details);
        return problem;
    }

    private void addPlatformFields(ProblemDetail problem, String error, List<String> details) {
        problem.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        problem.setProperty("error", error);
        problem.setProperty("traceId", currentTraceId());
        problem.setProperty("details", details);
    }

    private ErrorCode codeFor(Exception ex, HttpStatusCode statusCode) {
        return switch (ex) {
            case HttpMessageNotReadableException e -> ErrorCode.MALFORMED_REQUEST;
            case NoResourceFoundException e -> ErrorCode.NOT_FOUND;
            case HttpRequestMethodNotSupportedException e -> ErrorCode.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotSupportedException e -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> {
                if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
                    yield ErrorCode.VALIDATION_ERROR;
                }
                yield statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : null;
            }
        };
    }

    private List<String> detailsOf(Exception ex) {
        return switch (ex) {
            case MethodArgumentNotValidException e -> e.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .sorted()
                    .toList();
            case HandlerMethodValidationException e -> e.getParameterValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(error -> result.getMethodParameter().getParameterName() + ": " + error.getDefaultMessage()))
                    .sorted()
                    .toList();
            case TypeMismatchException e -> List.of(e.getPropertyName() + ": invalid value");
            case MissingServletRequestParameterException e -> List.of(e.getParameterName() + ": is required");
            case MissingRequestHeaderException e -> List.of(e.getHeaderName() + ": is required");
            default -> List.of();
        };
    }

    private String lastNode(String propertyPath) {
        return propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
    }

    private String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getRequestURI()
                : request.getDescription(false);
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span == null ? NO_TRACE : span.context().traceId();
    }
}
