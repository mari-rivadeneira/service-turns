package com.example.hexagonal.turns.domain.model;

import java.util.regex.Pattern;

/**
 * Telefono normalizado segun el contrato de la catedra: se quitan espacios, guiones, parentesis y puntos,
 * y debe cumplir ^\+?[0-9]{7,15}$.
 */
public record PhoneNumber(String value) {

    private static final Pattern VALID = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

    public PhoneNumber {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "El telefono debe tener entre 7 y 15 digitos, con un + inicial opcional");
        }
    }

    public static PhoneNumber of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("El telefono es obligatorio");
        }
        return new PhoneNumber(SEPARATORS.matcher(raw).replaceAll(""));
    }
}