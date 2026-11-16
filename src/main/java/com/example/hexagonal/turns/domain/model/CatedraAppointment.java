package com.example.hexagonal.turns.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record CatedraAppointment(
        Long reservationId,
        String reservationProcessId,
        String externalPatientId,
        LocalDate appointmentDate,
        LocalTime startTime,
        LocalTime endTime,
        Long professionalId,
        String professionalFirstName,
        String professionalLastName,
        String patientFirstName,
        String patientLastName,
        String patientPhone,
        Status status,
        Instant createdAt,
        Instant confirmedAt,
        Instant cancelledAt,
        String cancellationReason
) {
    public enum Status { PHONE_PENDING, CONFIRMED, CANCELLED, FAILED }
}