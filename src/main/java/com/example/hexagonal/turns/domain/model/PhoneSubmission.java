package com.example.hexagonal.turns.domain.model;

import java.time.Instant;

/** Telefono que se informa a la catedra (AdditionalInformationSubmitted). El numero ya esta normalizado. */
public record PhoneSubmission(String eventId, String reservationProcessId, String holdId, String requestEventId,
                              String phoneNumber, Instant occurredAt) {
}