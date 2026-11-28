package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.application.service.ReservationReconciler;
import com.example.hexagonal.turns.domain.model.CatedraAppointment;
import com.example.hexagonal.turns.domain.model.CatedraAppointmentPage;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.RecoverReservationsUseCase.RecoveryReport;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoverReservationsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final Instant PAST = NOW.minusSeconds(120);
    private static final Instant FUTURE = NOW.plusSeconds(600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate DATE = LocalDate.of(2026, 12, 7);

    @Mock
    private CatedraAppointmentClient catedraClient;

    private final InMemoryReservationProcessRepository repository = new InMemoryReservationProcessRepository();

    private RecoverReservationsUseCaseImpl useCase(int batchSize) {
        ReservationReconciler reconciler = new ReservationReconciler(repository, catedraClient, CLOCK);
        return new RecoverReservationsUseCaseImpl(repository, reconciler, CLOCK, 300_000L, batchSize);
    }

    private ReservationProcess started(int hour, Instant createdAt) {
        return repository.create(ReservationProcess.start(42L, "user-42", 15L, "Ana", "Gomez", DATE,
                LocalTime.of(hour, 0), LocalTime.of(hour, 30), "Juan", "Perez", createdAt));
    }

    private ReservationProcess inState(int hour, String tag, ReservationStatus status, Instant expiresAt) {
        ReservationProcess created = started(hour, NOW.minusSeconds(3600));
        repository.modify(created.getId(), p -> {
            p.setCatedraProcessId("process-" + tag);
            p.setHoldId("hold-" + tag);
            p.setRequestEventId("request-" + tag);
            p.setExpiresAt(expiresAt);
            p.setStatus(status);
        });
        return repository.get(created.getId());
    }

    private CatedraAppointment appointment(String tag, CatedraAppointment.Status status, Long reservationId) {
        return new CatedraAppointment(reservationId, "process-" + tag, "user-42", DATE, LocalTime.of(9, 0),
                LocalTime.of(9, 30), 15L, "Ana", "Gomez", "Juan", "Perez", "+5492615551234", status, NOW, null, null,
                null);
    }

    private void catedraReturns(CatedraAppointment... appointments) {
        when(catedraClient.listAppointments(any()))
                .thenReturn(new CatedraAppointmentPage(List.of(appointments), appointments.length));
    }

    private ReservationStatus statusOf(ReservationProcess process) {
        return repository.get(process.getId()).getStatus();
    }

    // ---------- procesos abandonados ----------

    @Test
    @DisplayName("Un proceso STARTED sin respuesta de la catedra pasado el tiempo de gracia se cierra como fallido")
    void shouldFailAbandonedStartedProcesses() {
        ReservationProcess abandoned = started(9, NOW.minusSeconds(600));
        ReservationProcess recent = started(10, NOW.minusSeconds(30));

        RecoveryReport report = useCase(50).recover();

        assertEquals(1, report.abandonedStarts());
        assertEquals(ReservationStatus.FAILED, statusOf(abandoned));
        assertEquals("START_ABANDONED", repository.get(abandoned.getId()).getStatusReason());
        assertEquals(ReservationStatus.STARTED, statusOf(recent));
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Si el proceso avanzo mientras tanto (conflicto de version), no se pisa: se cuenta como fallo y queda para la proxima")
    void shouldSkipAbandonedProcessesThatAdvancedConcurrently() {
        ReservationProcess abandoned = started(9, NOW.minusSeconds(600));
        repository.beforeUpdate(repository::bumpVersion);

        RecoveryReport report = useCase(50).recover();

        assertEquals(0, report.abandonedStarts());
        assertEquals(1, report.failures());
        assertEquals(ReservationStatus.STARTED, statusOf(abandoned));
    }

    // ---------- procesos vencidos ----------

    @Test
    @DisplayName("Un hold vencido que la catedra no conoce pasa a EXPIRED")
    void shouldExpireAnUnconfirmedHoldPastItsDeadline() {
        ReservationProcess held = inState(9, "held", ReservationStatus.HELD, PAST);
        catedraReturns();

        RecoveryReport report = useCase(50).recover();

        assertEquals(ReservationStatus.EXPIRED, statusOf(held));
        assertEquals(1, report.reconciled());
        assertEquals(1, report.changed());
    }

    @Test
    @DisplayName("Si se perdio el evento de confirmacion, el proceso vencido converge a CONFIRMED (la catedra decide)")
    void shouldRecoverALostConfirmationInsteadOfExpiring() {
        ReservationProcess submitted = inState(9, "sub", ReservationStatus.PHONE_SUBMITTED, PAST);
        catedraReturns(appointment("sub", CatedraAppointment.Status.CONFIRMED, 8251L));

        useCase(50).recover();

        ReservationProcess stored = repository.get(submitted.getId());
        assertEquals(ReservationStatus.CONFIRMED, stored.getStatus());
        assertEquals(8251L, stored.getReservationId());
    }

    @Test
    @DisplayName("Si se perdio el evento de vencimiento (la catedra la tiene FAILED), pasa a EXPIRED")
    void shouldRecoverALostExpiration() {
        ReservationProcess requested = inState(9, "req", ReservationStatus.PHONE_REQUESTED, PAST);
        catedraReturns(appointment("req", CatedraAppointment.Status.FAILED, null));

        useCase(50).recover();

        assertEquals(ReservationStatus.EXPIRED, statusOf(requested));
    }

    @Test
    @DisplayName("Un proceso que todavia no vencio no se toca ni se consulta a la catedra")
    void shouldLeaveProcessesThatHaveNotExpired() {
        ReservationProcess held = inState(9, "ok", ReservationStatus.HELD, FUTURE);

        RecoveryReport report = useCase(50).recover();

        assertEquals(ReservationStatus.HELD, statusOf(held));
        assertEquals(0, report.reconciled());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Es idempotente: una segunda corrida no encuentra nada que hacer")
    void shouldBeIdempotent() {
        inState(9, "held", ReservationStatus.HELD, PAST);
        catedraReturns();
        useCase(50).recover();

        RecoveryReport second = useCase(50).recover();

        assertEquals(0, second.reconciled());
        assertEquals(0, second.changed());
        verify(catedraClient, times(1)).listAppointments(any());
    }

    // ---------- fallos y limites ----------

    @Test
    @DisplayName("Si la catedra no responde, se corta la corrida, no se cambia nada y se informa")
    void shouldStopWhenTheCatedraIsUnavailable() {
        ReservationProcess first = inState(9, "a", ReservationStatus.HELD, PAST);
        ReservationProcess second = inState(10, "b", ReservationStatus.HELD, PAST);
        when(catedraClient.listAppointments(any()))
                .thenThrow(new CatedraUnavailableException("catedra caida", false, null));

        RecoveryReport report = useCase(50).recover();

        assertTrue(report.catedraUnavailable());
        assertEquals(ReservationStatus.HELD, statusOf(first));
        assertEquals(ReservationStatus.HELD, statusOf(second));
        verify(catedraClient, times(1)).listAppointments(any()); // no insiste con el segundo
    }

    @Test
    @DisplayName("Un fallo en un proceso no detiene a los demas")
    void shouldContinueAfterAFailureOfASingleProcess() {
        ReservationProcess first = inState(9, "a", ReservationStatus.HELD, PAST);
        ReservationProcess second = inState(10, "b", ReservationStatus.HELD, PAST);
        when(catedraClient.listAppointments(any()))
                .thenThrow(new IllegalStateException("configuracion de la catedra incompleta"))
                .thenReturn(new CatedraAppointmentPage(List.of(), 0));

        RecoveryReport report = useCase(50).recover();

        assertEquals(1, report.failures());
        assertFalse(report.catedraUnavailable());
        assertEquals(ReservationStatus.HELD, statusOf(first));
        assertEquals(ReservationStatus.EXPIRED, statusOf(second));
    }

    @Test
    @DisplayName("Cada corrida esta acotada por el tamano de lote")
    void shouldRespectTheBatchSize() {
        ReservationProcess a = inState(9, "a", ReservationStatus.HELD, PAST);
        ReservationProcess b = inState(10, "b", ReservationStatus.HELD, PAST);
        ReservationProcess c = inState(11, "c", ReservationStatus.HELD, PAST);
        catedraReturns();

        RecoveryReport report = useCase(2).recover();

        assertEquals(2, report.reconciled());
        long stillHeld = List.of(a, b, c).stream().filter(p -> statusOf(p) == ReservationStatus.HELD).count();
        assertEquals(1, stillHeld);
    }

    @Test
    @DisplayName("Sin nada pendiente no hace nada: ni consulta a la catedra ni cambia estados")
    void shouldDoNothingWhenThereIsNothingPending() {
        RecoveryReport report = useCase(50).recover();

        assertEquals(new RecoveryReport(0, 0, 0, 0, false), report);
        verifyNoInteractions(catedraClient);
        assertEquals(0, repository.count());
    }

    @Test
    @DisplayName("Los procesos en estado final o confirmados no se consultan")
    void shouldIgnoreFinalAndConfirmedProcesses() {
        inState(9, "exp", ReservationStatus.EXPIRED, PAST);
        inState(10, "can", ReservationStatus.CANCELLED, PAST);
        inState(11, "con", ReservationStatus.CONFIRMED, PAST);

        RecoveryReport report = useCase(50).recover();

        assertEquals(0, report.reconciled());
        verifyNoInteractions(catedraClient);
        assertTrue(repository.all().stream().map(ReservationProcess::getId).anyMatch(id -> id != null));
        assertEquals(3, repository.count());
        assertEquals(UUID.class, repository.all().get(0).getId().getClass());
    }
}