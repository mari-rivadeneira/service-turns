package com.example.hexagonal.turns.infrastructure.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** En los tests el job esta apagado (turns.recovery.enabled=false en la configuracion de prueba). */
@SpringBootTest
class ReservationRecoverySchedulerDisabledIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Con turns.recovery.enabled=false el job no existe")
    void shouldNotCreateTheSchedulerWhenDisabled() {
        assertEquals(0, context.getBeanNamesForType(ReservationRecoveryScheduler.class).length);
    }
}