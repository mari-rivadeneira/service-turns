package com.example.hexagonal.turns.domain.model;

import java.time.Instant;

public record CatedraCancellation(Long reservationId, String reservationProcessId, String status, Instant cancelledAt) {
}
