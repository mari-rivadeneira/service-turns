package com.example.hexagonal.turns.domain.model;

import java.time.Instant;

/** Evento de la catedra sobre un proceso de reserva (topic de acciones). Solo los campos que usa el servicio. */
public record AppointmentActionEvent(String eventId, Type type, String reservationProcessId, String holdId,
                                     String requestEventId, Long reservationId, String reason, Instant expiresAt) {

    public enum Type {
        /** AdditionalInformationRequested: la catedra pide el telefono. */
        REQUESTED,
        /** AppointmentConfirmed. */
        CONFIRMED,
        /** AdditionalInformationRejected: telefono rechazado; el proceso sigue vigente hasta expiresAt. */
        REJECTED,
        /** AppointmentProcessExpired. */
        EXPIRED,
        /** AppointmentProcessInvalid. */
        INVALID,
        /** AppointmentCancelled. */
        CANCELLED
    }
}