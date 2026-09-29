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

// Outbound HTTP - the only place that knows the addresses. Built from Boot's
// WebClient.Builder, which already traces every call as a child span.
// Timeouts are explicit: WebClient has none and would wait forever
@Configuration
@EnableConfigurationProperties({KeycloakProperties.class, PersonServiceProperties.class})
public class HttpClientsConfig {

    // "cannot connect" should fail fast, separately from "no answer"
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

    // Spring builds PersonsApi from its @HttpExchange annotations over WebClient
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
