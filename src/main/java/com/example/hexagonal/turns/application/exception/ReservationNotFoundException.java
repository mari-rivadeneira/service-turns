package com.example.hexagonal.turns.application.exception;

/** La reserva no existe o pertenece a otro usuario: las dos situaciones son indistinguibles a proposito. */
public class ReservationNotFoundException extends TurnsException {

    public ReservationNotFoundException() {
        super("La reserva no existe");
    }
}
