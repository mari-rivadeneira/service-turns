package com.example.hexagonal.turns.infrastructure.web.dto;

import com.example.hexagonal.turns.domain.model.Availability;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record AvailabilityResponse(long professionalId, LocalDate date, List<SlotResponse> slots) {

    public record SlotResponse(LocalTime startTime, LocalTime endTime) {
    }

    public static AvailabilityResponse from(Availability availability) {
        return new AvailabilityResponse(availability.professionalId(), availability.date(),
                availability.slots().stream()
                        .map(slot -> new SlotResponse(slot.startTime(), slot.endTime()))
                        .toList());
    }
}
