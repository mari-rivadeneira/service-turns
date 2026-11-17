package com.example.hexagonal.turns.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Turno no disponible segun la catedra: reserva confirmada o hold activo. */
public record SlotOccupancy(LocalDate date, LocalTime startTime, Status status, Instant expiresAt) {

    public enum Status { CONFIRMED, HELD }
}
