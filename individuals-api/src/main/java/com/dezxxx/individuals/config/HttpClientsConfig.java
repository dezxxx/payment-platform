package com.dezxxx.individuals.config;

import com.dezxxx.individuals.gateway.keycloak.client.KeycloakClient;
import com.dezxxx.person.client.api.IndividualsApi;
import com.dezxxx.transaction.client.api.TransactionsApi;
import com.dezxxx.transaction.client.api.WalletsApi;
import com.dezxxx.transaction.client.model.TransactionStatusDto;
import com.dezxxx.transaction.client.model.TransactionTypeDto;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties({KeycloakProperties.class,
                                PersonServiceProperties.class,
                                TransactionServiceProperties.class})
public class HttpClientsConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    WebClient keycloakWebClient(WebClient.Builder builder, KeycloakProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .clientConnector(connector(properties.responseTimeout()))
                .build();
    }

    @Bean
    WebClient personWebClient(WebClient.Builder builder, PersonServiceProperties properties,
                              KeycloakClient keycloakClient) {
        return builder
                .baseUrl(properties.baseUrl())
                .clientConnector(connector(properties.responseTimeout()))
                .filter(serviceToken(keycloakClient))
                .build();
    }

    @Bean
    WebClient transactionWebClient(WebClient.Builder builder, TransactionServiceProperties properties,
                                   KeycloakClient keycloakClient) {
        return builder
                .baseUrl(properties.baseUrl())
                .clientConnector(connector(properties.responseTimeout()))
                .filter(serviceToken(keycloakClient))
                .build();
    }

    @Bean
    IndividualsApi individualsApi(WebClient personWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(personWebClient))
                .build()
                .createClient(IndividualsApi.class);
    }

    @Bean
    WalletsApi walletsApi(WebClient transactionWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(transactionWebClient))
                .conversionService(transactionQueryValues())
                .build()
                .createClient(WalletsApi.class);
    }

    @Bean
    TransactionsApi transactionsApi(WebClient transactionWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(transactionWebClient))
                .conversionService(transactionQueryValues())
                .build()
                .createClient(TransactionsApi.class);
    }

    // ?type=deposit: by default the proxy writes an enum by its constant name (DEPOSIT), the contract wants the value
    public static ConversionService transactionQueryValues() {
        DefaultFormattingConversionService conversions = new DefaultFormattingConversionService();
        conversions.addConverter(TransactionTypeDto.class, String.class, TransactionTypeDto::getValue);
        conversions.addConverter(TransactionStatusDto.class, String.class, TransactionStatusDto::getValue);
        return conversions;
    }

    // person-service and transaction-service let in only a token with the realm role SERVICE: the service account
    // token of individuals-api, the one KeycloakClient already caches for the Admin API
    private static ExchangeFilterFunction serviceToken(KeycloakClient keycloakClient) {
        return (request, next) -> keycloakClient.adminLogin()
                .map(token -> ClientRequest.from(request).headers(headers -> headers.setBearerAuth(token)).build())
                .flatMap(next::exchange);
    }

    private static ReactorClientHttpConnector connector(Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(responseTimeout);
        return new ReactorClientHttpConnector(httpClient);
    }
}
