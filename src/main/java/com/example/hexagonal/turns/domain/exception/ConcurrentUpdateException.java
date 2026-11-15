package com.example.hexagonal.turns.domain.exception;

import java.util.UUID;

/** El proceso fue modificado por otra operacion despues de leerlo: hay que volver a leerlo y reintentar. */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(UUID processId, Throwable cause) {
        super("El proceso de reserva " + processId + " fue modificado por otra operacion", cause);
    }
}
