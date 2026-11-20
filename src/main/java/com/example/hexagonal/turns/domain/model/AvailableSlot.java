package com.example.hexagonal.turns.domain.model;

import java.time.LocalTime;

/** Turno disponible: hora de inicio y de fin, en hora local de atencion. */
public record AvailableSlot(LocalTime startTime, LocalTime endTime) {
}
