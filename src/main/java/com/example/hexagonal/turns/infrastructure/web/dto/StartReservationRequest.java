package com.example.hexagonal.turns.infrastructure.web.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Los campos son opcionales a proposito: el caso de uso valida y devuelve el detalle por campo. Un userId o un
 * externalPatientId enviados en el cuerpo se ignoran: la identidad sale del token.
 */
public record StartReservationRequest(Long professionalId, LocalDate date, LocalTime startTime,
                                      String patientFirstName, String patientLastName) {
}
