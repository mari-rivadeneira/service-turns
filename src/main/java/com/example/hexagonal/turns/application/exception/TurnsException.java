package com.example.hexagonal.turns.application.exception;

public class TurnsException extends RuntimeException {

    public TurnsException(String message) {
        super(message);
    }

    public TurnsException(String message, Throwable cause) {
        super(message, cause);
    }
}
