package com.dezxxx.individuals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dezxxx.individuals.integration.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

/**
 * IT-OBS-001, IT-OBS-002 and IT-OBS-003: the three things the module promises
 * about observability, each checked where we are actually responsible for it.
 *
 * <p>Note what IT-OBS-002 does <b>not</b> do: it does not start Tempo. Whether
 * Tempo indexes what it receives is Tempo's promise, not ours, and a container
 * for it would make the test slower and flakier while proving someone else's
 * code. Our promise ends at the socket - the application must build spans and
 * push them over OTLP - and that is what is asserted.
 */
@DisplayName("Observability")
class ObservabilityIT extends IntegrationTest {

    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private static final String REGISTRATION_PATH = "/api/v1/auth/registration";

    /** Registered in AuthMetrics, scraped with the suffixes Prometheus adds. */
    private static final List<String> SCRAPED_METER_NAMES = List.of(
            "auth_registration_total",
            "auth_registration_success_total",
            "auth_registration_failure_total",
            "auth_login_total",
            "auth_login_failure_total",
            "auth_refresh_total",
            "external_keycloak_requests_seconds_count",
            "external_person_service_requests_seconds_count");

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> capturedLogs;

    @BeforeEach
    void captureOurOwnLogs() {
        // Logback reads the MDC lazily, on first access, not when the record is
        // created. Read from the test thread afterwards it would always come
        // back empty, and the test would report "no trace id" about an
        // application that has one. prepareForDeferredProcessing pins the MDC
        // on the thread that did the logging - the same thing an async appender
        // does, and for the same reason.
        capturedLogs = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        capturedLogs.start();
        serviceLogger = (Logger) LoggerFactory.getLogger("com.dezxxx.individuals");
        serviceLogger.addAppender(capturedLogs);
    }

    @AfterEach
    void stopCapturing() {
        serviceLogger.detachAppender(capturedLogs);
        capturedLogs.stop();
    }

    @Test
    @DisplayName("IT-OBS-001: given the application is running, when /actuator/prometheus is scraped, then every meter is there")
    void publishesEveryMeterInPrometheusFormat() {
        // given - traffic, so the counters exist rather than merely being declared
        register(freshEmail("it-obs-001"));

        // when
        String scrape = client.get()
                .uri(PROMETHEUS_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(scrape).isNotNull();
        assertThat(SCRAPED_METER_NAMES).allSatisfy(meter -> assertThat(scrape).contains(meter));
    }

    @Test
    @DisplayName("IT-OBS-002: given a request has been served, when the exporter flushes, then spans reach the OTLP endpoint")
    void pushesTracesOverOtlp() {
        // given - whatever earlier tests already exported
        int exportsBefore = OTLP_COLLECTOR.exportedPayloadSizes().size();

        // when
        register(freshEmail("it-obs-002"));

        // then - the batch processor flushes on a schedule, so this is a wait,
        // not a poll for something that should already have happened
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<Integer> exports = OTLP_COLLECTOR.exportedPayloadSizes();
                    assertThat(exports).hasSizeGreaterThan(exportsBefore);
                    assertThat(exports.subList(exportsBefore, exports.size()))
                            .as("an export carrying at least one span")
                            .anySatisfy(size -> assertThat(size).isPositive());
                });
    }

    @Test
    @DisplayName("IT-OBS-003: given a business event is logged, when the record is read, then it carries traceId and spanId")
    void stampsTraceContextOnEveryLogRecord() {
        // given / when
        register(freshEmail("it-obs-003"));

        // then - the line RegistrationService writes on success, which is the
        // one an operator would follow from a dashboard into Tempo
        assertThat(capturedLogs.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("Registered"))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .as("MDC of [%s] logged on %s; every record captured: %s",
                                event.getFormattedMessage(),
                                event.getThreadName(),
                                capturedLogs.list.stream()
                                        .map(other -> other.getThreadName() + " " + other.getMDCPropertyMap())
                                        .toList())
                        .containsKeys("traceId", "spanId")
                        .doesNotContainValue(""));
    }

    @Test
    @DisplayName("IT-OBS-003: given a request is served, when its records are read, then they name the request and the user")
    void stampsTheRequestFieldsOnEveryRecord() {
        // given / when
        register(freshEmail("it-obs-003-fields"));

        // then - the module requires the path, the method and the domain user
        // on the records of a request, not only inside a message
        assertThat(capturedLogs.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("Registered"))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry("http.method", "POST")
                        .containsEntry("http.path", REGISTRATION_PATH)
                        .hasEntrySatisfying("user_uid",
                                uid -> assertThat(uid).isEqualTo(PERSON_SERVICE.userUid().toString())));
    }

    @Test
    @DisplayName("IT-OBS-003: given a request fails, when the summary is read, then it carries the status and the business error code")
    void stampsStatusAndErrorCodeOnTheSummary() {
        // given / when - a confirmation that does not match, rejected by @Valid
        client.post()
                .uri(REGISTRATION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationBody(freshEmail("it-obs-003-error"), "Str0ngPass!", "Mismatch1!"))
                .exchange()
                .expectStatus().isBadRequest();

        // then - the status exists only once the response is written, so it is
        // the closing summary that has to carry it, together with the code the
        // client was answered with
        assertThat(capturedLogs.list)
                .filteredOn(event -> event.getMDCPropertyMap().containsKey("http.status"))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry("http.status", "400")
                        .containsEntry("error.code", "VALIDATION_ERROR")
                        .containsEntry("http.method", "POST")
                        .containsKeys("traceId", "spanId"));
    }

    private void register(String email) {
        client.post()
                .uri(REGISTRATION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationBody(email, "Str0ngPass!", "Str0ngPass!"))
                .exchange()
                .expectStatus().isCreated();
    }
}
