package com.example.hexagonal.turns.infrastructure.scheduling;

import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Requiere el compose activo. Con el job habilitado, existe y una corrida no tira abajo al servicio. */
@SpringBootTest(properties = "turns.recovery.enabled=true")
class ReservationRecoverySchedulerEnabledIntegrationTest {

    @MockitoBean
    private CatedraAppointmentClient catedraClient;

    @Autowired(required = false)
    private ReservationRecoveryScheduler scheduler;

    @Test
    @DisplayName("Con turns.recovery.enabled=true el job existe y sus dos disparadores no lanzan excepciones")
    void shouldCreateTheSchedulerWhenEnabled() {
        assertNotNull(scheduler);

        assertDoesNotThrow(scheduler::recoverOnStartup);
        assertDoesNotThrow(scheduler::recoverPeriodically);
    }
}