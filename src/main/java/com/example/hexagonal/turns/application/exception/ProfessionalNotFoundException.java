package com.example.hexagonal.turns.application.exception;

public class ProfessionalNotFoundException extends TurnsException {

    public ProfessionalNotFoundException(long professionalId) {
        super("Profesional no encontrado: " + professionalId);
    }
}
