package com.example.hexagonal.turns.application.exception;

/** La reserva no se puede cancelar: la catedra la tiene en un estado que no lo permite. */
public class ReservationNotCancellableException extends TurnsException {

    public ReservationNotCancellableException() {
        super("La reserva no se puede cancelar en su estado actual");
    }
}