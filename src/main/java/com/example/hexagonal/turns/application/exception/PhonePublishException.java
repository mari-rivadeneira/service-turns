package com.example.hexagonal.turns.application.exception;

/** No se pudo publicar el telefono en Kafka: nada se guardo, y el usuario puede reintentar. */
public class PhonePublishException extends TurnsException {

    public PhonePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}