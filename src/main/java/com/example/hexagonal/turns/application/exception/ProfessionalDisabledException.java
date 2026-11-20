package com.example.hexagonal.turns.application.exception;

public class ProfessionalDisabledException extends TurnsException {

    public ProfessionalDisabledException(long professionalId) {
        super("El profesional " + professionalId + " no esta habilitado");
    }
}