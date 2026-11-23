package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.ReservationProcess;

import java.util.UUID;

public interface GetReservationUseCase {

    /** Solo devuelve la reserva si es del usuario: la de otro es indistinguible de una inexistente. */
    ReservationProcess get(long userId, UUID reservationId);
}