package com.example.hexagonal.turns.domain.exception;

import com.example.hexagonal.turns.domain.model.ReservationStatus;

public class InvalidStateTransitionException extends RuntimeException {

    private final ReservationStatus from;
    private final ReservationStatus to;

    public InvalidStateTransitionException(ReservationStatus from, ReservationStatus to) {
        super("Transicion de estado no permitida: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public ReservationStatus getFrom() { return from; }

    public ReservationStatus getTo() { return to; }
}