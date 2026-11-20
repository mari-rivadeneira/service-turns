package com.example.hexagonal.turns.application.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Datos de entrada invalidos, con el detalle por campo. */
public class InvalidRequestException extends TurnsException {

    private final Map<String, String> fieldErrors;

    public InvalidRequestException(Map<String, String> fieldErrors) {
        super("Datos invalidos: " + fieldErrors);
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
