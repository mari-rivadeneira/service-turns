package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.Availability;

import java.time.LocalDate;

public interface GetAvailabilityUseCase {

    /**
     * Turnos disponibles de un profesional en una fecha: agendas vigentes de service-catalog menos las ocupaciones de
     * la catedra. Es informativo: al crear el hold todavia se puede perder la carrera por un turno.
     *
     * @throws com.example.hexagonal.turns.application.exception.InvalidRequestException       datos invalidos
     * @throws com.example.hexagonal.turns.application.exception.ProfessionalNotFoundException  no existe
     * @throws com.example.hexagonal.turns.application.exception.ProfessionalDisabledException  esta deshabilitado
     */
    Availability getAvailability(AvailabilityQuery query);

    /** accessToken: JWT del usuario, que se reenvia a service-catalog para conservar su identidad. */
    record AvailabilityQuery(long professionalId, LocalDate date, String accessToken) {
    }
}
