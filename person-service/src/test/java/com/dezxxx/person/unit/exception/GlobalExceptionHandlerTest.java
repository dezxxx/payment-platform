package com.dezxxx.person.unit.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dezxxx.person.entity.IndividualEntity;
import com.dezxxx.person.exception.ErrorCode;
import com.dezxxx.person.exception.GlobalExceptionHandler;
import com.dezxxx.person.exception.PersonException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private static final String PATH = "/api/v1/individuals";

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
    @DisplayName("given a PersonException, when handled, then its ErrorCode decides status, error and type, and the message is the detail")
    void handlesPersonException() {
        // given
        PersonException ex = new PersonException(ErrorCode.COUNTRY_NOT_FOUND, "Unknown country code: XXX");

        // when
        ProblemDetail problem = handler.handlePerson(ex, request).getBody();

        // then
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Bad Request");
        assertThat(problem.getDetail()).isEqualTo("Unknown country code: XXX");
        assertThat(problem.getType()).isEqualTo(URI.create("https://example.org/problems/country-not-found"));
        assertThat(problem.getInstance()).isEqualTo(URI.create(PATH));
        assertThat(problem.getProperties())
                .containsEntry("error", "COUNTRY_NOT_FOUND")
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
        ProblemDetail problem = handler.handlePerson(new PersonException(ErrorCode.INDIVIDUAL_NOT_FOUND), request).getBody();

        // then
        assertThat(problem.getProperties()).containsEntry("traceId", "unavailable");
    }

    @Test
    @DisplayName("given a stale version, when handled, then 409 CONCURRENT_MODIFICATION")
    void handlesOptimisticLock() {
        // given
        ObjectOptimisticLockingFailureException ex = new ObjectOptimisticLockingFailureException(IndividualEntity.class, UUID.randomUUID());

        // when
        ResponseEntity<ProblemDetail> response = handler.handleOptimisticLock(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties()).containsEntry("error", "CONCURRENT_MODIFICATION");
    }

    @Test
    @DisplayName("given the email unique index is violated, when handled, then 409 EMAIL_ALREADY_EXISTS")
    void handlesEmailIndex() {
        // given
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"uk_users_email_lower\""));

        // when
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties()).containsEntry("error", "EMAIL_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("given another constraint is violated, when handled, then 500 without the database text")
    void handlesOtherIntegrityViolation() {
        // given
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("insert or update on table \"addresses\" violates foreign key constraint"));

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
        when(path.toString()).thenReturn("getIndividualByEmail.email");
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be a well-formed email address");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        // when
        ResponseEntity<ProblemDetail> response = handler.handleConstraintViolation(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties())
                .containsEntry("error", "VALIDATION_ERROR")
                .containsEntry("details", List.of("email: must be a well-formed email address"));
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
    @DisplayName("given a missing parameter, when Spring reports it, then 400 VALIDATION_ERROR says it is required")
    void handlesMissingParameter() throws Exception {
        // given
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("email", "String");

        // when
        ResponseEntity<Object> response = handler.handleException(ex, request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(((ProblemDetail) response.getBody()).getProperties())
                .containsEntry("details", List.of("email: is required"));
    }

    @Test
    @DisplayName("given an unsupported method, when Spring reports it, then 405 METHOD_NOT_ALLOWED")
    void handlesMethodNotAllowed() throws Exception {
        // when
        ResponseEntity<Object> response = handler.handleException(new HttpRequestMethodNotSupportedException("PUT"), request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(errorOf(response)).isEqualTo("METHOD_NOT_ALLOWED");
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
