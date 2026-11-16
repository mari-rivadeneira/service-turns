package com.example.hexagonal.turns.infrastructure.client.support;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class RestClients {

    private RestClients() {
    }

    /** RestClient con tiempos de espera de conexion y de lectura. */
    public static RestClient create(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(factory).build();
    }
}
