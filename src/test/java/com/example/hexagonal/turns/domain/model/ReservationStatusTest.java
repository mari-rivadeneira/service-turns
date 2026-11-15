package com.example.hexagonal.turns.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.example.hexagonal.turns.domain.model.ReservationStatus.CANCELLED;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.CONFIRMED;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.EXPIRED;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.FAILED;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.HELD;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.INVALID;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.PHONE_REJECTED;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.PHONE_REQUESTED;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.PHONE_SUBMITTED;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.STARTED;
import static com.example.hexagonal.turns.domain.model.ReservationStatus.WAITING_FOR_PHONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationStatusTest {

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CANCELLED", "EXPIRED", "INVALID", "FAILED"})
    @DisplayName("Los estados finales no tienen salida: un evento tardio no puede reabrirlos")
    void finalStatesHaveNoTransitions(ReservationStatus status) {
        assertTrue(status.isFinal());
        for (ReservationStatus target : ReservationStatus.values()) {
            assertFalse(status.canTransitionTo(target), status + " no deberia poder pasar a " + target);
        }
    }

    @Test
    @DisplayName("CONFIRMED no es final, pero solo admite la cancelacion")
    void confirmedCanOnlyBeCancelled() {
        assertFalse(CONFIRMED.isFinal());
        assertEquals(1, CONFIRMED.allowedTransitions().size());
        assertTrue(CONFIRMED.canTransitionTo(CANCELLED));
        assertFalse(CONFIRMED.canTransitionTo(EXPIRED));
        assertFalse(CONFIRMED.canTransitionTo(INVALID));
        assertFalse(CONFIRMED.canTransitionTo(FAILED));
        assertFalse(CONFIRMED.canTransitionTo(PHONE_SUBMITTED));
    }

    @Test
    @DisplayName("Ningun estado pasa a si mismo: un evento repetido no cambia nada")
    void noStateTransitionsToItself() {
        for (ReservationStatus status : ReservationStatus.values()) {
            assertFalse(status.canTransitionTo(status), status + " no deberia pasar a si mismo");
        }
    }

    @Test
    @DisplayName("El camino feliz esta permitido de punta a punta")
    void happyPathIsAllowed() {
        assertTrue(STARTED.canTransitionTo(HELD));
        assertTrue(HELD.canTransitionTo(WAITING_FOR_PHONE));
        assertTrue(WAITING_FOR_PHONE.canTransitionTo(PHONE_REQUESTED));
        assertTrue(PHONE_REQUESTED.canTransitionTo(PHONE_SUBMITTED));
        assertTrue(PHONE_SUBMITTED.canTransitionTo(CONFIRMED));
        assertTrue(CONFIRMED.canTransitionTo(CANCELLED));
    }

    @Test
    @DisplayName("Un telefono rechazado se puede reenviar")
    void rejectedPhoneCanBeResubmitted() {
        assertTrue(PHONE_SUBMITTED.canTransitionTo(PHONE_REJECTED));
        assertTrue(PHONE_REJECTED.canTransitionTo(PHONE_SUBMITTED));
    }

    @Test
    @DisplayName("El evento de la catedra puede llegar antes que la respuesta REST")
    void eventMayArriveBeforeRestResponse() {
        assertTrue(HELD.canTransitionTo(PHONE_REQUESTED));
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"HELD", "WAITING_FOR_PHONE", "PHONE_REQUESTED", "PHONE_SUBMITTED", "PHONE_REJECTED"})
    @DisplayName("Un proceso en curso puede vencer, invalidarse o fallar")
    void inProgressStatesCanEndInAFinalState(ReservationStatus status) {
        assertTrue(status.canTransitionTo(EXPIRED));
        assertTrue(status.canTransitionTo(INVALID));
        assertTrue(status.canTransitionTo(FAILED));
    }

    @Test
    @DisplayName("Un proceso que todavia no tiene hold solo puede avanzar o fallar")
    void startedCanOnlyAdvanceOrFail() {
        assertEquals(2, STARTED.allowedTransitions().size());
        assertFalse(STARTED.canTransitionTo(EXPIRED));
        assertFalse(STARTED.canTransitionTo(CONFIRMED));
    }

    @Test
    @DisplayName("Nada retrocede")
    void nothingMovesBackwards() {
        assertFalse(HELD.canTransitionTo(STARTED));
        assertFalse(WAITING_FOR_PHONE.canTransitionTo(HELD));
        assertFalse(PHONE_REQUESTED.canTransitionTo(WAITING_FOR_PHONE));
        assertFalse(PHONE_SUBMITTED.canTransitionTo(PHONE_REQUESTED));
        assertFalse(PHONE_REJECTED.canTransitionTo(PHONE_REQUESTED));
        assertFalse(CONFIRMED.canTransitionTo(PHONE_SUBMITTED));
    }
}