package com.example.hexagonal.turns.application.exception;

/** El turno ya fue tomado: la catedra tiene un hold o una reserva confirmada para ese horario. */
public class SlotUnavailableException extends TurnsException {

    public SlotUnavailableException() {
        super("El turno ya no esta disponible");
    }
}
