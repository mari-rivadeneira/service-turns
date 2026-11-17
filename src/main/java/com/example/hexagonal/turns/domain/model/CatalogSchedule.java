package com.example.hexagonal.turns.domain.model;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** Regla de agenda semanal vigente segun service-catalog. */
public record CatalogSchedule(long id, long professionalId, DayOfWeek dayOfWeek, LocalTime startTime,
                              LocalTime endTime, int slotDurationMinutes, boolean enabled) {
}