package com.example.hexagonal.turns.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfiguration {

    /** Reloj en la zona de atencion: las horas de las agendas son locales. Una zona invalida impide arrancar. */
    @Bean
    public Clock clock(@Value("${turns.timezone:America/Argentina/Buenos_Aires}") String zoneId) {
        return Clock.system(ZoneId.of(zoneId));
    }
}
