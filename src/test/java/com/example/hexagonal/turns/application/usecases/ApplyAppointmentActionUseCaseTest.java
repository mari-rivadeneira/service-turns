package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;
import com.example.hexagonal.turns.domain.model.AppointmentActionEvent.Type;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.ApplyAppointmentActionUseCase.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyAppointmentActionUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(600);
    private static final String PROCESS_ID = "process-a92b47d0";

    private final InMemoryReservationProcessRepository repository = new InMemoryReservationProcessRepository();
    private final InMemoryProcessedEventRepository processedEvents = new InMemoryProcessedEventRepository();
    private final ApplyAppointmentActionUseCaseImpl useCase =
            new ApplyAppointmentActionUseCaseImpl(repository, processedEvents, Clock.fixed(NOW, ZoneOffset.UTC));

    private ReservationProcess processIn(ReservationStatus status) {
        ReservationProcess created = repository.create(ReservationProcess.start(42L, "user-42", 15L, "Ana", "Gomez",
                LocalDate.of(2026, 12, 7), LocalTime.of(9, 30), LocalTime.of(10, 0), "Juan", "Perez", NOW));
        repository.modify(created.getId(), p -> {
            p.setCatedraProcessId(PROCESS_ID);
            p.setHoldId("hold-6c0c2f41");
            p.setExpiresAt(EXPIRES);
            p.setStatus(status);
        });
        return repository.get(created.getId());
    }

    private AppointmentActionEvent event(String eventId, Type type, Long reservationId, String reason,
                                         Instant expiresAt) {
        return new AppointmentActionEvent(eventId, type, PROCESS_ID, "hold-6c0c2f41", "request-event-8a31",
                reservationId, reason, expiresAt);
    }

    private ReservationProcess reload(ReservationProcess process) {
        return repository.get(process.getId());
    }

    // ---------- pedido de telefono ----------

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "WAITING_FOR_PHONE"})
    @DisplayName("Requested: guarda el eventId como requestEventId y pasa a PHONE_REQUESTED, tambien si llega antes que la respuesta REST")
    void shouldApplyRequested(ReservationStatus from) {
        ReservationProcess process = processIn(from);
        Instant newExpiry = EXPIRES.plusSeconds(60);

        Result result = useCase.apply(event("request-event-8a31", Type.REQUESTED, null, null, newExpiry));

        assertEquals(Result.APPLIED, result);
        ReservationProcess stored = reload(process);
        assertEquals(ReservationStatus.PHONE_REQUESTED, stored.getStatus());
        assertEquals("request-event-8a31", stored.getRequestEventId());
        assertEquals(newExpiry, stored.getExpiresAt());
        assertTrue(processedEvents.exists("request-event-8a31"));
    }

    @Test
    @DisplayName("Un segundo pedido de telefono con otro eventId no cambia el requestEventId")
    void shouldIgnoreASecondRequest() {
        ReservationProcess process = processIn(ReservationStatus.WAITING_FOR_PHONE);
        useCase.apply(event("request-event-1", Type.REQUESTED, null, null, EXPIRES));

        Result result = useCase.apply(event("request-event-2", Type.REQUESTED, null, null, EXPIRES));

        assertEquals(Result.NOT_APPLICABLE, result);
        assertEquals("request-event-1", reload(process).getRequestEventId());
    }

    // ---------- confirmacion ----------

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"WAITING_FOR_PHONE", "PHONE_REQUESTED", "PHONE_SUBMITTED"})
    @DisplayName("Confirmed: pasa a CONFIRMED con el id de la reserva, tambien si se perdio algun paso anterior")
    void shouldApplyConfirmed(ReservationStatus from) {
        ReservationProcess process = processIn(from);

        Result result = useCase.apply(event("confirmation-event-1", Type.CONFIRMED, 8251L, null, null));

        assertEquals(Result.APPLIED, result);
        ReservationProcess stored = reload(process);
        assertEquals(ReservationStatus.CONFIRMED, stored.getStatus());
        assertEquals(8251L, stored.getReservationId());
        assertEquals(NOW, stored.getConfirmedAt());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"EXPIRED", "INVALID", "CANCELLED", "FAILED"})
    @DisplayName("Una confirmacion tardia no reabre un estado final")
    void shouldNotConfirmAFinalProcess(ReservationStatus finalStatus) {
        ReservationProcess process = processIn(finalStatus);

        Result result = useCase.apply(event("confirmation-late", Type.CONFIRMED, 8251L, null, null));

        assertEquals(Result.NOT_APPLICABLE, result);
        ReservationProcess stored = reload(process);
        assertEquals(finalStatus, stored.getStatus());
        assertNull(stored.getReservationId());
    }

    @Test
    @DisplayName("Una segunda confirmacion con otro eventId no cambia la reserva ya confirmada")
    void shouldIgnoreASecondConfirmation() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED);
        useCase.apply(event("confirmation-1", Type.CONFIRMED, 8251L, null, null));

        Result result = useCase.apply(event("confirmation-2", Type.CONFIRMED, 9999L, null, null));

        assertEquals(Result.NOT_APPLICABLE, result);
        assertEquals(8251L, reload(process).getReservationId());
    }

    // ---------- rechazo ----------

    @Test
    @DisplayName("Rejected: PHONE_REJECTED con el motivo y la nueva vigencia; el proceso sigue vigente")
    void shouldApplyRejected() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED);
        Instant newExpiry = EXPIRES.plusSeconds(120);

        Result result = useCase.apply(event("rejection-1", Type.REJECTED, null, "INVALID_PHONE_NUMBER", newExpiry));

        assertEquals(Result.APPLIED, result);
        ReservationProcess stored = reload(process);
        assertEquals(ReservationStatus.PHONE_REJECTED, stored.getStatus());
        assertEquals("INVALID_PHONE_NUMBER", stored.getStatusReason());
        assertEquals(newExpiry, stored.getExpiresAt());
    }

    @Test
    @DisplayName("Un rechazo tardio no afecta a una reserva ya confirmada")
    void shouldNotRejectAConfirmedReservation() {
        ReservationProcess process = processIn(ReservationStatus.CONFIRMED);

        assertEquals(Result.NOT_APPLICABLE, useCase.apply(event("rejection-late", Type.REJECTED, null, "X", EXPIRES)));

        assertEquals(ReservationStatus.CONFIRMED, reload(process).getStatus());
    }

    // ---------- vencimiento e invalido ----------

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"HELD", "WAITING_FOR_PHONE", "PHONE_REQUESTED", "PHONE_SUBMITTED", "PHONE_REJECTED"})
    @DisplayName("Expired: cierra el proceso en curso con el motivo")
    void shouldApplyExpired(ReservationStatus from) {
        ReservationProcess process = processIn(from);

        Result result = useCase.apply(event("expired-1", Type.EXPIRED, null, "HOLD_EXPIRED", null));

        assertEquals(Result.APPLIED, result);
        assertEquals(ReservationStatus.EXPIRED, reload(process).getStatus());
        assertEquals("HOLD_EXPIRED", reload(process).getStatusReason());
    }

    @Test
    @DisplayName("Expired sin motivo usa HOLD_EXPIRED")
    void shouldDefaultTheExpirationReason() {
        ReservationProcess process = processIn(ReservationStatus.HELD);

        useCase.apply(event("expired-2", Type.EXPIRED, null, null, null));

        assertEquals("HOLD_EXPIRED", reload(process).getStatusReason());
    }

    @Test
    @DisplayName("Un vencimiento tardio no afecta a una reserva confirmada")
    void shouldNotExpireAConfirmedReservation() {
        ReservationProcess process = processIn(ReservationStatus.CONFIRMED);

        assertEquals(Result.NOT_APPLICABLE, useCase.apply(event("expired-late", Type.EXPIRED, null, "HOLD_EXPIRED", null)));

        assertEquals(ReservationStatus.CONFIRMED, reload(process).getStatus());
    }

    @Test
    @DisplayName("Invalid: el proceso pasa a INVALID con el motivo; no afecta a una reserva confirmada")
    void shouldApplyInvalid() {
        ReservationProcess submitted = processIn(ReservationStatus.PHONE_SUBMITTED);

        assertEquals(Result.APPLIED, useCase.apply(event("invalid-1", Type.INVALID, null, "REQUEST_EVENT_MISMATCH", null)));
        assertEquals(ReservationStatus.INVALID, reload(submitted).getStatus());
        assertEquals("REQUEST_EVENT_MISMATCH", reload(submitted).getStatusReason());

        ReservationProcess confirmed = processIn(ReservationStatus.CONFIRMED);
        assertEquals(Result.NOT_APPLICABLE, useCase.apply(event("invalid-2", Type.INVALID, null, "X", null)));
        assertEquals(ReservationStatus.CONFIRMED, reload(confirmed).getStatus());
    }

    // ---------- cancelacion ----------

    @Test
    @DisplayName("Cancelled: una reserva confirmada pasa a CANCELLED, con o sin motivo")
    void shouldApplyCancelled() {
        ReservationProcess process = processIn(ReservationStatus.CONFIRMED);

        Result result = useCase.apply(event("cancellation-1", Type.CANCELLED, 8251L, null, null));

        assertEquals(Result.APPLIED, result);
        ReservationProcess stored = reload(process);
        assertEquals(ReservationStatus.CANCELLED, stored.getStatus());
        assertEquals(NOW, stored.getCancelledAt());
        assertNull(stored.getStatusReason());
    }

    @Test
    @DisplayName("Si la cancelacion ya se registro (la hizo este mismo servicio), el evento no cambia nada")
    void shouldIgnoreTheEchoOfOurOwnCancellation() {
        ReservationProcess process = processIn(ReservationStatus.CANCELLED);

        assertEquals(Result.NOT_APPLICABLE, useCase.apply(event("cancellation-echo", Type.CANCELLED, 8251L, "x", null)));

        assertEquals(ReservationStatus.CANCELLED, reload(process).getStatus());
    }

    @Test
    @DisplayName("Solo se cancela una reserva confirmada")
    void shouldNotCancelAnUnconfirmedProcess() {
        ReservationProcess process = processIn(ReservationStatus.HELD);

        assertEquals(Result.NOT_APPLICABLE, useCase.apply(event("cancellation-2", Type.CANCELLED, null, null, null)));

        assertEquals(ReservationStatus.HELD, reload(process).getStatus());
    }

    // ---------- idempotencia ----------

    @Test
    @DisplayName("El mismo eventId dos veces: la segunda es un duplicado y no cambia nada")
    void shouldTreatTheSameEventIdAsDuplicate() {
        ReservationProcess process = processIn(ReservationStatus.WAITING_FOR_PHONE);
        AppointmentActionEvent requested = event("request-event-8a31", Type.REQUESTED, null, null, EXPIRES);

        assertEquals(Result.APPLIED, useCase.apply(requested));
        ReservationProcess afterFirst = reload(process);
        assertEquals(Result.DUPLICATE, useCase.apply(requested));

        assertEquals(afterFirst.getVersion(), reload(process).getVersion());
        assertEquals(1, repository.count());
    }

    @Test
    @DisplayName("Un evento que no aplica igual queda registrado: su segunda entrega es un duplicado")
    void shouldRegisterEventsThatDoNotApply() {
        processIn(ReservationStatus.CONFIRMED);
        AppointmentActionEvent late = event("expired-late", Type.EXPIRED, null, "HOLD_EXPIRED", null);

        assertEquals(Result.NOT_APPLICABLE, useCase.apply(late));
        assertEquals(Result.DUPLICATE, useCase.apply(late));
    }

    @Test
    @DisplayName("Un evento de un proceso desconocido se descarta y NO se registra")
    void shouldDiscardEventsOfUnknownProcessesWithoutRegisteringThem() {
        AppointmentActionEvent unknown = new AppointmentActionEvent("unknown-1", Type.REQUESTED, "process-desconocido",
                "hold-x", null, null, null, EXPIRES);

        assertEquals(Result.UNKNOWN_PROCESS, useCase.apply(unknown));

        assertFalse(processedEvents.exists("unknown-1"));
    }
}