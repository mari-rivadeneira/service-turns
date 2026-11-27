package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.ReservationProcess;

import java.util.UUID;

public interface CancelReservationUseCase {

    /**
     * Cancela una reserva confirmada del usuario. Es idempotente: una ya cancelada devuelve su estado. Si la llamada a la
     * catedra no se completa, el proceso sigue confirmado y repetir es seguro (la cancelacion es idempotente alli).
     */
    ReservationProcess cancel(long userId, UUID reservationId, String reason);
}