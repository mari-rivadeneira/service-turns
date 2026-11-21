package com.example.hexagonal.turns.application.exception;

/** El bloqueo temporal del turno venció o la catedra ya no lo conoce. */
public class HoldExpiredException extends TurnsException {

    public HoldExpiredException() {
        super("El bloqueo del turno ya no esta vigente");
    }
}