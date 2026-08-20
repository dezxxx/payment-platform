package com.dezxxx.individuals.config;

import com.dezxxx.person.client.api.PersonsApi;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import reactor.netty.http.client.HttpClient;

/**
 * Outbound HTTP. The only place in the service that knows an address.
 *
 * <p>Both clients are built from the auto-configured {@link WebClient.Builder}
 * rather than from {@code WebClient.create()}: the builder Spring Boot hands
 * out already carries the observation instrumentation, so every outgoing call
 * becomes a child span of the request that caused it. A hand-made client would
 * silently break the trace at our own boundary.
 *
 * <p>Timeouts are set explicitly. A WebClient has none by default, and an
 * unanswered call to Keycloak would otherwise keep a caller waiting forever
 * instead of turning into the 503 the contract describes.
 */
@Configuration
@EnableConfigurationProperties({KeycloakProperties.class, PersonServiceProperties.class})
public class HttpClientsConfig {

    /**
     * Connect timeout is separate from the response timeout: refusing to answer
     * and refusing to accept a connection are different failures, and the
     * second one should be reported quickly.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    WebClient keycloakWebClient(WebClient.Builder builder, KeycloakProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .clientConnector(connector(properties.responseTimeout()))
                .build();
    }

    @Bean
    WebClient personWebClient(WebClient.Builder builder, PersonServiceProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .clientConnector(connector(properties.responseTimeout()))
                .build();
    }

    /**
     * The generated client interface is never implemented by hand: Spring
     * builds a proxy from its {@code @HttpExchange} annotations, backed by the
     * reactive WebClient, so every operation returns a Mono.
     */
    @Bean
    PersonsApi personsApi(WebClient personWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(personWebClient))
                .build()
                .createClient(PersonsApi.class);
    }

    private static ReactorClientHttpConnector connector(Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(responseTimeout);
        return new ReactorClientHttpConnector(httpClient);
    }
}
