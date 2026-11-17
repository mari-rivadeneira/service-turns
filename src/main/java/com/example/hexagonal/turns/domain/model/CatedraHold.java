package com.example.hexagonal.turns.domain.model;

import java.time.Instant;

/** Hold temporal creado en la catedra (REST 201). */
public record CatedraHold(String holdId, String reservationProcessId, String status, Instant expiresAt) {
}