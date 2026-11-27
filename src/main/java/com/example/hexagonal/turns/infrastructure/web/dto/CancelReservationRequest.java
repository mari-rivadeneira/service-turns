package com.example.hexagonal.turns.infrastructure.web.dto;

/** El cuerpo entero es opcional, igual que el motivo. */
public record CancelReservationRequest(String reason) {
}
