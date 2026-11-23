package com.example.hexagonal.turns.domain.port.in;

import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;

public interface ProcessAppointmentActionUseCase {

    /** Aplica el evento reintentando de forma acotada los conflictos de version. */
    ApplyAppointmentActionUseCase.Result process(AppointmentActionEvent event);
}