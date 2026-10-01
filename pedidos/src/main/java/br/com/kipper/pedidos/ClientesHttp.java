package br.com.kipper.pedidos;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class ClientesHttp {
    private ClientesHttp() { }

    static RestClient criar(String endereco, long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("O timeout precisa ser positivo.");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().baseUrl(endereco).requestFactory(factory).build();
    }
}
