package com.example.hexagonal.turns.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneNumberTest {

    @Test
    @DisplayName("Quita espacios, guiones, parentesis y puntos, y conserva el + inicial")
    void shouldNormalize() {
        assertEquals("+542615551234", PhoneNumber.of("+54 (261) 555-1234").value());
        assertEquals("2615551234", PhoneNumber.of("261.555.1234").value());
        assertEquals("2615551234", PhoneNumber.of("  2615551234 ").value());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "123456", "1234567890123456", "26155+51234", "++5426155512", "261-555-12a4"})
    @DisplayName("Un telefono invalido se rechaza")
    void shouldRejectInvalidNumbers(String raw) {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumber.of(raw));
    }
}