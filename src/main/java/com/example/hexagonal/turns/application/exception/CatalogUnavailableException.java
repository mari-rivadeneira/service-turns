package com.example.hexagonal.turns.application.exception;

/** service-catalog no responde o respondio con un error del servidor: no se puede validar informacion vigente. */
public class CatalogUnavailableException extends TurnsException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
