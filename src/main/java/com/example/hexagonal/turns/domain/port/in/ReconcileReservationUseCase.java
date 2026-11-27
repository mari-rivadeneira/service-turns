package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.ReservationProcess;

import java.util.UUID;

public interface ReconcileReservationUseCase {

    /** Consulta a la catedra el estado real de una reserva propia y hace converger el estado local, sin retroceder. */
    ReservationProcess reconcile(long userId, UUID reservationId);
}