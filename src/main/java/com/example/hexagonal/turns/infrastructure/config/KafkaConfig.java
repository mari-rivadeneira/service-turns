package com.example.hexagonal.turns.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /**
     * Un fallo recuperable (la base caida, por ejemplo) se reintenta 4 veces con 2 segundos de espera. Si sigue fallando, el
     * mensaje se registra en el log y se continua, para no bloquear la particion. Spring Boot aplica este manejador al
     * contenedor del listener.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2000L, 4L));
    }
}
