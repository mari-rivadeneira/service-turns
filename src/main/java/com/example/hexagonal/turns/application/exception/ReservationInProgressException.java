package com.example.hexagonal.turns.application.exception;

/** Hay un pedido del mismo usuario para ese turno que todavia se esta procesando. */
public class ReservationInProgressException extends TurnsException {

    public ReservationInProgressException() {
        super("Ya hay una reserva en curso para ese turno");
    }
}