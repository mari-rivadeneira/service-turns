package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.ReservationProcess;

import java.util.UUID;

public interface ConfirmReservationUseCase {

    /**
     * Confirmacion inicial del hold: la catedra pasa a pedir el telefono. Es idempotente: si el proceso ya avanzo,
     * lo devuelve tal como esta sin volver a llamar a la catedra. Solo opera sobre procesos del usuario.
     */
    ReservationProcess confirm(long userId, UUID reservationId);
}