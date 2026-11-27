package com.example.hexagonal.turns.application.service;

import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.application.usecases.InMemoryReservationProcessRepository;
import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.model.AppointmentQuery;
import com.example.hexagonal.turns.domain.model.CatedraAppointment;
import com.example.hexagonal.turns.domain.model.CatedraAppointmentPage;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final Instant FUTURE = NOW.plusSeconds(600);
    private static final Instant PAST = NOW.minusSeconds(60);
    private static final LocalDate DATE = LocalDate.of(2026, 12, 7);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private CatedraAppointmentClient catedraClient;

    private final InMemoryReservationProcessRepository repository = new InMemoryReservationProcessRepository();

    private ReservationReconciler reconciler() {
        return new ReservationReconciler(repository, catedraClient, CLOCK);
    }

    private ReservationProcess processIn(ReservationStatus status, Instant expiresAt) {
        ReservationProcess created = repository.create(ReservationProcess.start(42L, "user-42", 15L, "Ana", "Gomez",
                DATE, LocalTime.of(9, 30), LocalTime.of(10, 0), "Juan", "Perez", NOW.minusSeconds(900)));
        repository.modify(created.getId(), p -> {
            p.setCatedraProcessId("process-a92b47d0");
            p.setHoldId("hold-6c0c2f41");
            p.setRequestEventId("request-event-8a31");
            p.setExpiresAt(expiresAt);
            p.setStatus(status);
        });
        return repository.get(created.getId());
    }

    private CatedraAppointment appointment(CatedraAppointment.Status status, Long reservationId, String reason) {
        return new CatedraAppointment(reservationId, "process-a92b47d0", "user-42", DATE, LocalTime.of(9, 30),
                LocalTime.of(10, 0), 15L, "Ana", "Gomez", "Juan", "Perez", "+5492615551234", status,
                NOW.minusSeconds(800), null, null, reason);
    }

    private void catedraReturns(CatedraAppointment... appointments) {
        when(catedraClient.listAppointments(any()))
                .thenReturn(new CatedraAppointmentPage(List.of(appointments), appointments.length));
    }

    private ReservationProcess reload(ReservationProcess process) {
        return repository.get(process.getId());
    }

    // ---------- la catedra confirmo ----------

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"WAITING_FOR_PHONE", "PHONE_REQUESTED", "PHONE_SUBMITTED"})
    @DisplayName("Si la catedra la tiene CONFIRMED y se perdio el evento, el proceso converge a CONFIRMED")
    void shouldConvergeToConfirmedWhenTheEventWasLost(ReservationStatus from) {
        ReservationProcess process = processIn(from, FUTURE);
        catedraReturns(appointment(CatedraAppointment.Status.CONFIRMED, 8251L, null));

        ReservationProcess result = reconciler().reconcile(process);

        assertEquals(ReservationStatus.CONFIRMED, result.getStatus());
        assertEquals(8251L, result.getReservationId());
        assertEquals(NOW, result.getConfirmedAt());
        assertEquals(ReservationStatus.CONFIRMED, reload(process).getStatus());
    }

    @Test
    @DisplayName("Si ya estaba CONFIRMED y la catedra tambien, no cambia nada")
    void shouldNotChangeAnAlreadyConfirmedReservation() {
        ReservationProcess process = processIn(ReservationStatus.CONFIRMED, FUTURE);
        repository.modify(process.getId(), p -> p.setReservationId(8251L));
        catedraReturns(appointment(CatedraAppointment.Status.CONFIRMED, 8251L, null));
        long versionBefore = reload(process).getVersion();

        reconciler().reconcile(process);

        assertEquals(versionBefore, reload(process).getVersion());
    }

    // ---------- la catedra cancelo ----------

    @Test
    @DisplayName("Si la catedra la tiene CANCELLED y el proceso seguia CONFIRMED, pasa a CANCELLED con el motivo")
    void shouldConvergeToCancelled() {
        ReservationProcess process = processIn(ReservationStatus.CONFIRMED, FUTURE);
        repository.modify(process.getId(), p -> p.setReservationId(8251L));
        catedraReturns(appointment(CatedraAppointment.Status.CANCELLED, 8251L, "Cambio solicitado"));

        ReservationProcess result = reconciler().reconcile(process);

        assertEquals(ReservationStatus.CANCELLED, result.getStatus());
        assertEquals("Cambio solicitado", result.getStatusReason());
        assertEquals(NOW, result.getCancelledAt());
    }

    @Test
    @DisplayName("Si se perdieron la confirmacion y la cancelacion, se reflejan las dos en orden")
    void shouldApplyConfirmationAndCancellationWhenBothEventsWereLost() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED, FUTURE);
        catedraReturns(appointment(CatedraAppointment.Status.CANCELLED, 8251L, "Cambio solicitado"));

        ReservationProcess result = reconciler().reconcile(process);

        assertEquals(ReservationStatus.CANCELLED, result.getStatus());
        assertEquals(8251L, result.getReservationId());
        assertEquals(NOW, result.getConfirmedAt());
        assertEquals(NOW, result.getCancelledAt());
    }

    // ---------- la catedra fallo ----------

    @Test
    @DisplayName("FAILED en la catedra con la vigencia cumplida: el proceso pasa a EXPIRED")
    void shouldMarkExpiredWhenTheCatedraFailedAfterTheDeadline() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED, PAST);
        catedraReturns(appointment(CatedraAppointment.Status.FAILED, null, null));

        ReservationProcess result = reconciler().reconcile(process);

        assertEquals(ReservationStatus.EXPIRED, result.getStatus());
        assertEquals("HOLD_EXPIRED", result.getStatusReason());
    }

    @Test
    @DisplayName("FAILED en la catedra antes de la vigencia: el proceso pasa a FAILED")
    void shouldMarkFailedWhenTheCatedraFailedBeforeTheDeadline() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED, FUTURE);
        catedraReturns(appointment(CatedraAppointment.Status.FAILED, null, null));

        ReservationProcess result = reconciler().reconcile(process);

        assertEquals(ReservationStatus.FAILED, result.getStatus());
        assertEquals("CATEDRA_FAILED", result.getStatusReason());
    }

    // ---------- sin cambios ----------

    @Test
    @DisplayName("Si la catedra sigue esperando el telefono (PHONE_PENDING), no cambia nada")
    void shouldNotChangeWhileTheCatedraStillWaitsForThePhone() {
        ReservationProcess process = processIn(ReservationStatus.WAITING_FOR_PHONE, FUTURE);
        catedraReturns(appointment(CatedraAppointment.Status.PHONE_PENDING, null, null));

        ReservationProcess result = reconciler().reconcile(process);

        assertEquals(ReservationStatus.WAITING_FOR_PHONE, result.getStatus());
    }

    @Test
    @DisplayName("Nunca retrocede: una reserva CONFIRMED no vuelve atras aunque la catedra informe PHONE_PENDING")
    void shouldNeverRegress() {
        ReservationProcess process = processIn(ReservationStatus.CONFIRMED, FUTURE);
        repository.modify(process.getId(), p -> p.setReservationId(8251L));
        catedraReturns(appointment(CatedraAppointment.Status.PHONE_PENDING, null, null));

        assertEquals(ReservationStatus.CONFIRMED, reconciler().reconcile(process).getStatus());
    }

    // ---------- la catedra no la conoce ----------

    @Test
    @DisplayName("Un hold sin confirmar que ya vencio y la catedra no conoce: pasa a EXPIRED")
    void shouldExpireAnUnconfirmedHoldPastItsDeadline() {
        ReservationProcess process = processIn(ReservationStatus.HELD, PAST);
        when(catedraClient.listAppointments(any())).thenReturn(new CatedraAppointmentPage(List.of(), 0));

        ReservationProcess result = reconciler().reconcile(process);

        assertEquals(ReservationStatus.EXPIRED, result.getStatus());
        assertEquals("HOLD_EXPIRED", result.getStatusReason());
    }

    @Test
    @DisplayName("Si el hold sigue vigente, o el proceso ya paso de HELD, la catedra sin datos no cambia nada")
    void shouldNotChangeWhenTheCatedraDoesNotKnowItButItIsNotExpiredHeld() {
        ReservationProcess heldInTime = processIn(ReservationStatus.HELD, FUTURE);
        ReservationProcess waiting = processIn(ReservationStatus.WAITING_FOR_PHONE, PAST);
        when(catedraClient.listAppointments(any())).thenReturn(new CatedraAppointmentPage(List.of(), 0));

        assertEquals(ReservationStatus.HELD, reconciler().reconcile(heldInTime).getStatus());
        assertEquals(ReservationStatus.WAITING_FOR_PHONE, reconciler().reconcile(waiting).getStatus());
    }

    // ---------- cuando no se consulta ----------

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"EXPIRED", "INVALID", "CANCELLED", "FAILED"})
    @DisplayName("Un estado final no se reconcilia ni se consulta a la catedra")
    void shouldSkipFinalProcesses(ReservationStatus status) {
        ReservationProcess process = processIn(status, PAST);

        assertEquals(status, reconciler().reconcile(process).getStatus());

        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Un proceso sin datos de la catedra (STARTED) no se consulta")
    void shouldSkipProcessesWithoutCatedraData() {
        ReservationProcess started = repository.create(ReservationProcess.start(42L, "user-42", 15L, "Ana", "Gomez",
                DATE, LocalTime.of(11, 0), LocalTime.of(11, 30), "Juan", "Perez", NOW));

        assertEquals(ReservationStatus.STARTED, reconciler().reconcile(started).getStatus());

        verifyNoInteractions(catedraClient);
    }

    // ---------- la consulta ----------

    @Test
    @DisplayName("Consulta a la catedra por el paciente y el dia del turno")
    void shouldQueryByPatientAndDate() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED, FUTURE);
        catedraReturns(appointment(CatedraAppointment.Status.PHONE_PENDING, null, null));

        reconciler().reconcile(process);

        ArgumentCaptor<AppointmentQuery> captor = ArgumentCaptor.forClass(AppointmentQuery.class);
        verify(catedraClient).listAppointments(captor.capture());
        assertNull(captor.getValue().status());
        assertEquals("user-42", captor.getValue().externalPatientId());
        assertEquals(DATE, captor.getValue().from());
        assertEquals(DATE, captor.getValue().to());
        assertEquals(0, captor.getValue().page());
    }

    @Test
    @DisplayName("Si no esta en la primera pagina, sigue buscando en la siguiente")
    void shouldLookInTheFollowingPages() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED, FUTURE);
        CatedraAppointment other = new CatedraAppointment(1L, "otro-proceso", "user-42", DATE, LocalTime.of(8, 0),
                LocalTime.of(8, 30), 15L, null, null, null, null, null, CatedraAppointment.Status.CONFIRMED,
                NOW, null, null, null);
        when(catedraClient.listAppointments(any()))
                .thenReturn(new CatedraAppointmentPage(List.of(other), 120))
                .thenReturn(new CatedraAppointmentPage(
                        List.of(appointment(CatedraAppointment.Status.CONFIRMED, 8251L, null)), 120));

        ReservationProcess result = reconciler().reconcile(process);

        assertEquals(ReservationStatus.CONFIRMED, result.getStatus());
        verify(catedraClient, times(2)).listAppointments(any());
    }

    // ---------- fallos y concurrencia ----------

    @Test
    @DisplayName("Si la catedra no responde, se informa y el estado no cambia")
    void shouldPropagateCatedraUnavailableWithoutChangingTheState() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED, FUTURE);
        when(catedraClient.listAppointments(any()))
                .thenThrow(new CatedraUnavailableException("catedra caida", false, null));

        assertThrows(CatedraUnavailableException.class, () -> reconciler().reconcile(process));

        assertEquals(ReservationStatus.PHONE_SUBMITTED, reload(process).getStatus());
    }

    @Test
    @DisplayName("Un conflicto de version se relee y se reintenta, con la consulta a la catedra hecha una sola vez")
    void shouldRetryAVersionConflict() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED, FUTURE);
        catedraReturns(appointment(CatedraAppointment.Status.CONFIRMED, 8251L, null));
        AtomicBoolean conflictInjected = new AtomicBoolean(false);
        repository.beforeUpdate(id -> {
            if (conflictInjected.compareAndSet(false, true)) {
                repository.bumpVersion(id);
            }
        });

        ReservationProcess result = reconciler().reconcile(process);

        assertEquals(ReservationStatus.CONFIRMED, result.getStatus());
        verify(catedraClient, times(1)).listAppointments(any());
    }

    @Test
    @DisplayName("Los reintentos por conflicto tienen tope")
    void shouldGiveUpAfterTheMaximumConflicts() {
        ReservationProcess process = processIn(ReservationStatus.PHONE_SUBMITTED, FUTURE);
        catedraReturns(appointment(CatedraAppointment.Status.CONFIRMED, 8251L, null));
        repository.beforeUpdate(repository::bumpVersion);

        assertThrows(ConcurrentUpdateException.class, () -> reconciler().reconcile(process));
    }
}