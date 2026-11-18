package com.dezxxx.currency.config;

import com.dezxxx.currency.client.OxrApi;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
@EnableConfigurationProperties(RateProviderProperties.class)
public class RateProviderClientConfig {

    private static final String TOKEN_PREFIX = "Token ";

    @Bean
    public OxrApi oxrApi(RestClient.Builder builder, RateProviderProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        // the JDK factory's read timeout covers the whole body, not only the wait for the headers
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.responseTimeout());

        // the key goes in a header: a query parameter would land in traces and access logs
        RestClient restClient = builder
                .baseUrl(properties.oxr().url())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, TOKEN_PREFIX + properties.oxr().appId())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(OxrApi.class);
    }
}
