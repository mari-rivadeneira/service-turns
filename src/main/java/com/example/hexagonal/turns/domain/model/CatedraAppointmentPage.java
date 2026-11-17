package com.example.hexagonal.turns.domain.model;

import java.util.List;

public record CatedraAppointmentPage(List<CatedraAppointment> content, long totalCount) {

    public CatedraAppointmentPage {
        content = List.copyOf(content);
    }
}
