package com.example.hexagonal.turns.infrastructure.client.catedra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** DTO del contrato REST de la catedra (version 1). Las respuestas ignoran los campos desconocidos. */
public final class CatedraApiDtos {

    private CatedraApiDtos() {
    }

    /** date y startTime van como texto en el formato del contrato (yyyy-MM-dd y HH:mm). */
    public record HoldRequest(long professionalId, String date, String startTime, String externalPatientId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HoldResponse(String holdId, String reservationProcessId, String status, Instant expiresAt) {
    }

    public record ConfirmRequest(String externalPatientId, String patientFirstName, String patientLastName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfirmResponse(String reservationProcessId, String status, Instant expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OccupanciesResponse(Long professionalId, List<OccupancyItem> occupancies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OccupancyItem(LocalDate date, LocalTime startTime, String status, Instant expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AppointmentResponse(
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
            String status,
            Instant createdAt,
            Instant confirmedAt,
            Instant cancelledAt,
            String cancellationReason
    ) {
    }

    public record CancelRequest(String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancelResponse(Long reservationId, String reservationProcessId, String status, Instant cancelledAt) {
    }
}
