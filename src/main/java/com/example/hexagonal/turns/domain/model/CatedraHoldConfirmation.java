package com.example.hexagonal.turns.domain.model;

import java.time.Instant;

/** Confirmacion inicial aceptada por la catedra (REST 202). */
public record CatedraHoldConfirmation(String reservationProcessId, String status, Instant expiresAt) {
}
