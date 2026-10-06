package com.dezxxx.person.exception;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// Every failure becomes one RFC 9457 body: type, title, status, detail, instance
// plus the course fields timestamp, error, traceId, details.
// The parent class turns Spring's own exceptions (bad JSON, 405, 415...) into a
// ProblemDetail; handleExceptionInternal below gives them our fields too.
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String PROBLEM_TYPE_BASE = "https://example.org/problems/";

    // traceId is required by the contract, so there is always a value
    private static final String NO_TRACE = "unavailable";

    // V003: the unique index that keeps emails unique regardless of case
    private static final String EMAIL_INDEX = "uk_users_email_lower";

    private final Tracer tracer;

    @ExceptionHandler(PersonException.class)
    public ResponseEntity<Object> handlePerson(PersonException ex, WebRequest request) {
        return problem(ex, ex.getErrorCode(), ex.getMessage(), List.of(), request);
    }

    // two requests read the same version; the second one to write loses
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, WebRequest request) {
        ErrorCode code = ErrorCode.CONCURRENT_MODIFICATION;
        return problem(ex, code, code.getDefaultMessage(), List.of(), request);
    }

    // two creates with one email both pass the existsByEmail check; the index stops the second
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        if (violates(ex, EMAIL_INDEX)) {
            ErrorCode code = ErrorCode.EMAIL_ALREADY_EXISTS;
            return problem(ex, code, code.getDefaultMessage(), List.of(), request);
        }
        return handleUnexpected(ex, request);
    }

    // method validation on @RequestParam (the generated interface is @Validated)
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(violation -> lastNode(violation.getPropertyPath().toString()) + ": " + violation.getMessage())
                .sorted()
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return problem(ex, code, code.getDefaultMessage(), details, request);
    }

    // anything else: 500, and the client never sees the exception text
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return problem(ex, code, code.getDefaultMessage(), List.of(), request);
    }

    // Spring's own exceptions arrive here from the parent class
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = codeFor(ex, statusCode);
        if (code == null) {
            // a rare framework status we have no code for (406...): keep Spring's answer, add our fields
            ProblemDetail problem = ProblemDetail.forStatus(statusCode);
            addCourseFields(problem, HttpStatus.valueOf(statusCode.value()).name(), List.of());
            problem.setInstance(URI.create(path(request)));
            log.warn("{} -> {}: {}", path(request), statusCode.value(), ex.getMessage());
            return ResponseEntity.status(statusCode).headers(headers).body(problem);
        }
        return problem(ex, code, code.getDefaultMessage(), detailsOf(ex), request);
    }

    private ResponseEntity<Object> problem(Exception ex, ErrorCode code, String detail,
                                           List<String> details, WebRequest request) {
        String path = path(request);
        if (code.getStatus().is5xxServerError()) {
            log.error("{} -> {} {}", path, code.getStatus().value(), code, ex);
        } else {
            log.warn("{} -> {} {}: {}", path, code.getStatus().value(), code, detail);
        }

        // title is the HTTP reason phrase, set by forStatusAndDetail
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.getStatus(), detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create(path));
        addCourseFields(problem, code.name(), details);
        return ResponseEntity.status(code.getStatus()).body(problem);
    }

    // properties are written at the top level of the JSON, next to the RFC 9457 fields
    private void addCourseFields(ProblemDetail problem, String error, List<String> details) {
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
            // @Valid body, method validation, a bad UUID in the path, a missing parameter
            default -> {
                if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
                    yield ErrorCode.VALIDATION_ERROR;
                }
                yield statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : null;
            }
        };
    }

    // one readable line per failed field, e.g. "email: must be a well-formed email address"
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
            default -> List.of();
        };
    }

    private boolean violates(DataIntegrityViolationException ex, String constraint) {
        String message = ex.getMostSpecificCause().getMessage();
        return message != null && message.contains(constraint);
    }

    // "getUserByEmail.email" -> "email"
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
