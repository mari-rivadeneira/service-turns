package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;

public interface ApplyAppointmentActionUseCase {

    /**
     * Aplica un evento de la catedra en UNA transaccion: el registro del eventId y el cambio de estado se confirman
     * juntos o ninguno. Un conflicto de version lanza ConcurrentUpdateException (lo reintenta quien llama, con una
     * transaccion nueva).
     */
    Result apply(AppointmentActionEvent event);

    enum Result {
        /** El estado cambio. */
        APPLIED,
        /** El evento es valido pero no corresponde al estado actual (tardio o repetido): se ignora, y queda registrado. */
        NOT_APPLICABLE,
        /** Ya se habia procesado ese eventId. */
        DUPLICATE,
        /** No hay un proceso local con ese reservationProcessId: no se registra, la recuperacion por REST lo resuelve. */
        UNKNOWN_PROCESS
    }
}