package com.example.hexagonal.turns.infrastructure.web.dto;

import com.example.hexagonal.turns.domain.model.ReservationProcess;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Vista de una reserva para su dueno. No expone los identificadores internos de la catedra ni el usuario. */
public record ReservationResponse(
        UUID id,
        String status,
        String statusReason,
        Long professionalId,
        String professionalFirstName,
        String professionalLastName,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        String patientFirstName,
        String patientLastName,
        Instant expiresAt,
        Long reservationId,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt,
        Instant cancelledAt
) {
    public static ReservationResponse from(ReservationProcess p) {
        return new ReservationResponse(p.getId(), p.getStatus().name(), p.getStatusReason(), p.getProfessionalId(),
                p.getProfessionalFirstName(), p.getProfessionalLastName(), p.getAppointmentDate(), p.getStartTime(),
                p.getEndTime(), p.getPatientFirstName(), p.getPatientLastName(), p.getExpiresAt(),
                p.getReservationId(), p.getCreatedAt(), p.getUpdatedAt(), p.getConfirmedAt(), p.getCancelledAt());
    }
}