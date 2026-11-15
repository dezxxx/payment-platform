package com.dezxxx.individuals.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.config.DependencyFailurePredicate;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@DisplayName("DependencyFailurePredicate")
class DependencyFailurePredicateTest {

    private final DependencyFailurePredicate predicate = new DependencyFailurePredicate();

    @Test
    @DisplayName("given a 404 answer, when classifying, then it is not a failure - the service works")
    void notFoundIsNotFailure() {
        // given
        WebClientResponseException answer = response(HttpStatus.NOT_FOUND);

        // when / then
        assertThat(predicate.test(answer)).isFalse();
    }

    @Test
    @DisplayName("given a 400 answer, when classifying, then it is not a failure")
    void badRequestIsNotFailure() {
        // given
        WebClientResponseException answer = response(HttpStatus.BAD_REQUEST);

        // when / then
        assertThat(predicate.test(answer)).isFalse();
    }

    @Test
    @DisplayName("given a 503 answer, when classifying, then it is a failure")
    void serverErrorIsFailure() {
        // given
        WebClientResponseException answer = response(HttpStatus.SERVICE_UNAVAILABLE);

        // when / then
        assertThat(predicate.test(answer)).isTrue();
    }

    @Test
    @DisplayName("given a refused connection or a timeout, when classifying, then it is a failure")
    void transportErrorIsFailure() {
        // when / then
        assertThat(predicate.test(new ConnectException("Connection refused"))).isTrue();
        assertThat(predicate.test(new TimeoutException("no answer in 5s"))).isTrue();
    }

    private static WebClientResponseException response(HttpStatus status) {
        return WebClientResponseException.create(status.value(), status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], null);
    }
}
