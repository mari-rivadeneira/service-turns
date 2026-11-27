package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;

import java.util.List;

public interface ListReservationsUseCase {

    int MAX_RESULTS = 100;

    /** Reservas del usuario, de la mas nueva a la mas vieja (hasta MAX_RESULTS), opcionalmente de un estado. */
    List<ReservationProcess> list(long userId, ReservationStatus status);
}