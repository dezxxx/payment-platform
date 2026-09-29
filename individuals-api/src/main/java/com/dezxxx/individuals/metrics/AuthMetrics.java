package com.dezxxx.individuals.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

// All our metrics, and the only place their names are written - a dashboard
// depends on these strings. Dotted Micrometer names: Prometheus adds _total
// itself, so auth.registration is scraped as auth_registration_total
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

    // attempts that passed validation - a 400 never reaches the service
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

    // wrong password and Keycloak down count the same; the ErrorCode tells them apart
    public void loginFailed() {
        loginFailures.increment();
    }

    public void refreshStarted() {
        refreshes.increment();
    }

    // one call to Keycloak, OIDC and Admin API alike
    public <T> Mono<T> timeKeycloak(Mono<T> call) {
        return time(keycloakRequests, call);
    }

    // one call to person-service
    public <T> Mono<T> timePersonService(Mono<T> call) {
        return time(personServiceRequests, call);
    }

    // defer: the stopwatch starts on subscribe, not when the chain is built.
    // doFinally: failed and cancelled calls took time too
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
