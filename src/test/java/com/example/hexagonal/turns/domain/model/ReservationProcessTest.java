package com.example.hexagonal.turns.domain.model;

import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationProcessTest {

    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final Instant EXPIRES = NOW.plusSeconds(600);

    private ReservationProcess started() {
        return ReservationProcess.start(42L, "user-42", 15L, "Ana", "Gomez",
                LocalDate.of(2026, 12, 1), LocalTime.of(9, 30), LocalTime.of(10, 0), "Juan", "Perez", NOW);
    }

    private ReservationProcess held() {
        ReservationProcess process = started();
        process.holdCreated("process-a92b47d0", "hold-6c0c2f41", EXPIRES, NOW);
        return process;
    }

    private ReservationProcess requested() {
        ReservationProcess process = held();
        process.initialConfirmationAccepted(EXPIRES, NOW);
        process.phoneRequested("request-event-8a31", EXPIRES, NOW);
        return process;
    }

    private ReservationProcess submitted() {
        ReservationProcess process = requested();
        process.phoneSubmitted(PhoneNumber.of("+54 261 555-1234"), NOW);
        return process;
    }

    private ReservationProcess confirmed() {
        ReservationProcess process = submitted();
        process.confirmed(8251L, LATER);
        return process;
    }

    @Test
    @DisplayName("Un proceso nuevo queda en STARTED, con su dueno y sin datos de la catedra")
    void shouldStartWithOwnerAndNoCatedraData() {
        ReservationProcess process = started();

        assertNotNull(process.getId());
        assertEquals(ReservationStatus.STARTED, process.getStatus());
        assertTrue(process.isOwnedBy(42L));
        assertFalse(process.isOwnedBy(43L));
        assertEquals("user-42", process.getExternalPatientId());
        assertNull(process.getCatedraProcessId());
        assertNull(process.getHoldId());
        assertNull(process.getReservationId());
        assertEquals(NOW, process.getCreatedAt());
        assertFalse(process.isFinal());
    }

    @Test
    @DisplayName("Datos de inicio invalidos se rechazan")
    void shouldRejectInvalidStartData() {
        LocalDate date = LocalDate.of(2026, 12, 1);
        LocalTime nine = LocalTime.of(9, 0);

        assertThrows(IllegalArgumentException.class, () -> ReservationProcess.start(
                null, "user-1", 15L, null, null, date, nine, null, "Juan", "Perez", NOW));
        assertThrows(IllegalArgumentException.class, () -> ReservationProcess.start(
                1L, "x".repeat(101), 15L, null, null, date, nine, null, "Juan", "Perez", NOW));
        assertThrows(IllegalArgumentException.class, () -> ReservationProcess.start(
                1L, "user-1", 15L, null, null, null, nine, null, "Juan", "Perez", NOW));
        assertThrows(IllegalArgumentException.class, () -> ReservationProcess.start(
                1L, "user-1", 15L, null, null, date, nine, nine, "Juan", "Perez", NOW));
        assertThrows(IllegalArgumentException.class, () -> ReservationProcess.start(
                1L, "user-1", 15L, null, null, date, nine, null, "J", "Perez", NOW));
        assertThrows(IllegalArgumentException.class, () -> ReservationProcess.start(
                1L, "user-1", 15L, null, null, date, nine, null, "Juan", "x".repeat(101), NOW));
    }

    @Test
    @DisplayName("Camino feliz: del hold a la reserva confirmada y su cancelacion")
    void shouldFollowHappyPath() {
        ReservationProcess process = held();
        assertEquals(ReservationStatus.HELD, process.getStatus());
        assertEquals("process-a92b47d0", process.getCatedraProcessId());
        assertEquals("hold-6c0c2f41", process.getHoldId());
        assertEquals(EXPIRES, process.getExpiresAt());

        process.initialConfirmationAccepted(EXPIRES, NOW);
        assertEquals(ReservationStatus.WAITING_FOR_PHONE, process.getStatus());

        process.phoneRequested("request-event-8a31", EXPIRES, NOW);
        assertEquals(ReservationStatus.PHONE_REQUESTED, process.getStatus());
        assertEquals("request-event-8a31", process.getRequestEventId());

        process.phoneSubmitted(PhoneNumber.of("+54 261 555-1234"), NOW);
        assertEquals(ReservationStatus.PHONE_SUBMITTED, process.getStatus());
        assertEquals("+542615551234", process.getPatientPhone());

        process.confirmed(8251L, LATER);
        assertEquals(ReservationStatus.CONFIRMED, process.getStatus());
        assertEquals(8251L, process.getReservationId());
        assertEquals(LATER, process.getConfirmedAt());
        assertFalse(process.isFinal());

        process.cancelled("Cambio solicitado por el usuario", LATER);
        assertEquals(ReservationStatus.CANCELLED, process.getStatus());
        assertEquals(LATER, process.getCancelledAt());
        assertEquals("Cambio solicitado por el usuario", process.getStatusReason());
        assertTrue(process.isFinal());
    }

    @Test
    @DisplayName("Telefono rechazado: el proceso sigue vigente y se puede reenviar con el mismo requestEventId")
    void shouldAllowResubmittingAfterRejection() {
        ReservationProcess process = submitted();

        process.phoneRejected("INVALID_PHONE_NUMBER", EXPIRES.plusSeconds(10), LATER);
        assertEquals(ReservationStatus.PHONE_REJECTED, process.getStatus());
        assertEquals("INVALID_PHONE_NUMBER", process.getStatusReason());
        assertEquals(EXPIRES.plusSeconds(10), process.getExpiresAt());
        assertEquals("request-event-8a31", process.getRequestEventId());
        assertFalse(process.isFinal());

        process.phoneSubmitted(PhoneNumber.of("2615559999"), LATER);
        assertEquals(ReservationStatus.PHONE_SUBMITTED, process.getStatus());
        assertEquals("2615559999", process.getPatientPhone());
        assertNull(process.getStatusReason());
    }

    @Test
    @DisplayName("Si el evento llega antes que la respuesta REST, la respuesta tardia se ignora sin retroceder")
    void shouldNotGoBackWhenEventArrivesBeforeRestResponse() {
        ReservationProcess process = held();

        process.phoneRequested("request-event-8a31", EXPIRES, LATER);

        assertFalse(process.canTransitionTo(ReservationStatus.WAITING_FOR_PHONE));
        assertThrows(InvalidStateTransitionException.class,
                () -> process.initialConfirmationAccepted(EXPIRES, LATER));
        assertEquals(ReservationStatus.PHONE_REQUESTED, process.getStatus());
    }

    @Test
    @DisplayName("Un evento repetido no cambia el estado")
    void shouldRejectSameStateTransition() {
        ReservationProcess process = requested();

        assertFalse(process.canTransitionTo(ReservationStatus.PHONE_REQUESTED));
        assertThrows(InvalidStateTransitionException.class,
                () -> process.phoneRequested("request-event-OTRO", EXPIRES, LATER));
        assertEquals("request-event-8a31", process.getRequestEventId());
    }

    @Test
    @DisplayName("Un proceso vencido no se puede confirmar: los estados finales no retroceden")
    void shouldNotConfirmAnExpiredProcess() {
        ReservationProcess process = held();
        process.expired("HOLD_EXPIRED", LATER);

        assertThrows(InvalidStateTransitionException.class, () -> process.confirmed(8251L, LATER));
        assertThrows(InvalidStateTransitionException.class, () -> process.invalidated("X", LATER));

        assertEquals(ReservationStatus.EXPIRED, process.getStatus());
        assertNull(process.getReservationId());
        assertNull(process.getConfirmedAt());
    }

    @Test
    @DisplayName("Una reserva confirmada no vence ni se invalida: solo se cancela")
    void shouldNotExpireAConfirmedReservation() {
        ReservationProcess process = confirmed();

        assertThrows(InvalidStateTransitionException.class, () -> process.expired("HOLD_EXPIRED", LATER));
        assertThrows(InvalidStateTransitionException.class, () -> process.invalidated("X", LATER));
        assertEquals(ReservationStatus.CONFIRMED, process.getStatus());
    }

    @Test
    @DisplayName("Una reserva cancelada no vuelve a confirmarse")
    void shouldNotReconfirmACancelledReservation() {
        ReservationProcess process = confirmed();
        process.cancelled(null, LATER);

        assertThrows(InvalidStateTransitionException.class, () -> process.confirmed(8251L, LATER));
        assertEquals(ReservationStatus.CANCELLED, process.getStatus());
    }

    @Test
    @DisplayName("Solo se cancelan reservas confirmadas")
    void shouldOnlyCancelConfirmedReservations() {
        ReservationProcess process = held();

        assertThrows(InvalidStateTransitionException.class, () -> process.cancelled("motivo", LATER));
        assertEquals(ReservationStatus.HELD, process.getStatus());
        assertNull(process.getCancelledAt());
    }

    @Test
    @DisplayName("Una transicion rechazada no modifica nada del proceso")
    void shouldNotChangeAnythingWhenTransitionIsRejected() {
        ReservationProcess process = held();

        assertThrows(InvalidStateTransitionException.class,
                () -> process.phoneSubmitted(PhoneNumber.of("2615551234"), LATER));

        assertEquals(ReservationStatus.HELD, process.getStatus());
        assertNull(process.getPatientPhone());
        assertEquals(NOW, process.getUpdatedAt());
    }

    @Test
    @DisplayName("Datos obligatorios faltantes en una transicion: se rechaza y el estado no cambia")
    void shouldRejectMissingDataWithoutChangingState() {
        ReservationProcess process = started();

        assertThrows(IllegalArgumentException.class, () -> process.holdCreated("process-1", " ", EXPIRES, NOW));
        assertThrows(IllegalArgumentException.class, () -> process.holdCreated("process-1", "hold-1", null, NOW));

        assertEquals(ReservationStatus.STARTED, process.getStatus());
        assertNull(process.getHoldId());
    }

    @Test
    @DisplayName("Un proceso que nunca llego a la catedra puede fallar")
    void shouldFailWhenHoldWasNeverCreated() {
        ReservationProcess process = started();

        process.failed("La catedra no respondio", LATER);

        assertEquals(ReservationStatus.FAILED, process.getStatus());
        assertTrue(process.isFinal());
    }

    @Test
    @DisplayName("canExpire: vencido y todavia no final ni confirmado")
    void shouldDetectProcessesThatCanExpire() {
        ReservationProcess process = held();

        assertFalse(process.canExpire(EXPIRES.minusSeconds(1)));
        assertTrue(process.canExpire(EXPIRES));
        assertTrue(process.canExpire(EXPIRES.plusSeconds(1)));
        assertFalse(started().canExpire(EXPIRES.plusSeconds(1000)));
        assertFalse(confirmed().canExpire(EXPIRES.plusSeconds(1000)));
    }

    @Test
    @DisplayName("El motivo largo se trunca al maximo de la columna")
    void shouldTruncateLongReasons() {
        ReservationProcess process = held();

        process.invalidated("x".repeat(600), LATER);

        assertEquals(500, process.getStatusReason().length());
    }
}
