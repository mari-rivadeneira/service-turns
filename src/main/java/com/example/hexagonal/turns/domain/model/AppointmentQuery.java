package com.example.hexagonal.turns.domain.model;

import java.time.LocalDate;

/** Filtros de la consulta de reservas de la catedra. Los filtros en null no se envian. */
public record AppointmentQuery(
        CatedraAppointment.Status status,
        LocalDate from,
        LocalDate to,
        String externalPatientId,
        int page,
        int size
) {
    public AppointmentQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page no puede ser negativa");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size debe ser al menos 1");
        }
    }
}
