package com.example.hexagonal.turns.infrastructure.client.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** DTO del contrato de service-catalog. Se ignoran los campos desconocidos. */
public final class CatalogApiDtos {

    private CatalogApiDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfessionalResponse(long id, long categoryId, String firstName, String lastName, boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleResponse(long id, long professionalId, DayOfWeek dayOfWeek, LocalTime startTime,
                                   LocalTime endTime, int slotDurationMinutes, boolean enabled) {
    }
}