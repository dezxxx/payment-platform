package com.dezxxx.individuals.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Every meter this service publishes, and the only place their names are typed.
 *
 * <p>A metric name is a contract, the same way an {@code ErrorCode} is: a
 * Grafana panel and an alert rule are written against the string, and renaming
 * it silently empties a dashboard nobody is looking at right now. So the eight
 * names live in one file - a query that looks odd can be traced back here
 * instead of grepped for across the gateways and the services.
 *
 * <p>Micrometer's dotted convention, <b>not</b> the {@code _total} suffix the
 * handout's table shows. Prometheus appends {@code _total} to counters when it
 * scrapes, so a counter registered as {@code auth_registration_total} arrives
 * as {@code auth_registration_total_total}. The handout lists the scraped
 * names; what is registered here is what produces them.
 */
@Component
public class AuthMetrics {

    private final MeterRegistry registry;

    private final Counter registrations;

    private final Counter registrationSuccesses;

    private final Counter registrationFailures;

    private final Counter logins;

    private final Counter loginFailures;

    private final Counter refreshes;

    private final Timer keycloakRequests;

    private final Timer personServiceRequests;

    public AuthMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.registrations = counter(registry, "auth.registration",
                "Registration attempts, counted when the request starts");
        this.registrationSuccesses = counter(registry, "auth.registration.success",
                "Registrations that ended with a token pair");
        this.registrationFailures = counter(registry, "auth.registration.failure",
                "Registrations that ended with an error, for any reason");
        this.logins = counter(registry, "auth.login", "Login attempts");
        this.loginFailures = counter(registry, "auth.login.failure",
                "Logins Keycloak refused, plus logins it never answered");
        this.refreshes = counter(registry, "auth.refresh", "Token refresh attempts");
        this.keycloakRequests = timer(registry, "external.keycloak.requests",
                "Time spent inside one call to Keycloak, OIDC and Admin alike");
        this.personServiceRequests = timer(registry, "external.person_service.requests",
                "Time spent inside one call to person-service");
    }

    /**
     * Attempts, not requests that got past validation: a request rejected with
     * <b>400 (Bad Request)</b> never reaches a service, so the ratio of this
     * counter to the two below says what happens to the registrations we
     * actually tried to carry out.
     */
    public void registrationStarted() {
        registrations.increment();
    }

    public void registrationSucceeded() {
        registrationSuccesses.increment();
    }

    public void registrationFailed() {
        registrationFailures.increment();
    }

    public void loginStarted() {
        logins.increment();
    }

    /**
     * Deliberately does not separate "wrong password" from "Keycloak is down".
     * The distinction is already in the answer's {@code ErrorCode} and in the
     * logs; a second dimension here would only invite an alert that fires on
     * users mistyping their passwords.
     */
    public void loginFailed() {
        loginFailures.increment();
    }

    public void refreshStarted() {
        refreshes.increment();
    }

    /** Times one call to Keycloak - both its OIDC and its Admin API. */
    public <T> Mono<T> timeKeycloak(Mono<T> call) {
        return time(keycloakRequests, call);
    }

    /** Times one call to person-service. */
    public <T> Mono<T> timePersonService(Mono<T> call) {
        return time(personServiceRequests, call);
    }

    /**
     * Wrapped in {@code defer} so the stopwatch starts when someone subscribes,
     * not when the chain is assembled - otherwise a call that is built but
     * never subscribed would still be timed, and a retried one would report the
     * age of the chain rather than the duration of the attempt.
     *
     * <p>{@code doFinally} rather than {@code doOnSuccess}: a call that failed
     * or was cancelled took time too, and leaving those out would make the
     * timer flatter than reality exactly when something is wrong.
     */
    private <T> Mono<T> time(Timer timer, Mono<T> call) {
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start(registry);
            return call.doFinally(signal -> sample.stop(timer));
        });
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    private static Timer timer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name).description(description).register(registry);
    }
}
