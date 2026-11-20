package com.example.hexagonal.turns.domain.model;

import java.time.LocalDate;
import java.util.List;

public record Availability(long professionalId, LocalDate date, List<AvailableSlot> slots) {

    public Availability {
        slots = List.copyOf(slots);
    }
}