package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.ReservationProcess;

import java.util.UUID;

public interface SubmitPhoneUseCase {

    /**
     * El usuario informa su telefono: se publica en Kafka y el proceso pasa a PHONE_SUBMITTED. Es idempotente: repetir el
     * mismo telefono, o enviarlo cuando la reserva ya avanzo, devuelve el estado actual sin publicar de nuevo. Solo opera
     * sobre procesos del usuario.
     */
    ReservationProcess submit(long userId, UUID reservationId, String phoneNumber);
}
