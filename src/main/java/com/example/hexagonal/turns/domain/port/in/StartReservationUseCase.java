package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.ReservationProcess;

import java.time.LocalDate;
import java.time.LocalTime;

public interface StartReservationUseCase {

    /**
     * Inicia la reserva de un turno: valida contra service-catalog, guarda el proceso ANTES de llamar a la catedra y
     * crea el hold. Si el mismo usuario repite el pedido para el mismo turno, devuelve el proceso existente.
     */
    StartReservationResult start(StartReservationCommand command);

    /** userId sale del token; accessToken es el JWT del usuario, que se reenvia a service-catalog. */
    record StartReservationCommand(long userId, String accessToken, Long professionalId, LocalDate date,
                                   LocalTime startTime, String patientFirstName, String patientLastName) {
    }

    /** created es false cuando el pedido repetido encontro un proceso ya existente. */
    record StartReservationResult(ReservationProcess process, boolean created) {
    }
}