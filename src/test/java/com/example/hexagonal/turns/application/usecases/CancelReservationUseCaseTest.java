package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.application.exception.InvalidRequestException;
import com.example.hexagonal.turns.application.exception.ReservationNotCancellableException;
import com.example.hexagonal.turns.application.exception.ReservationNotFoundException;
import com.example.hexagonal.turns.application.service.ReservationReconciler;
import com.example.hexagonal.turns.application.service.ReservationTransitions;
import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;
import com.example.hexagonal.turns.domain.model.CatedraCancellation;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelReservationUseCaseTest {

    private static final long USER = 42L;
    private static final long OTHER_USER = 43L;
    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final CatedraCancellation CANCELLATION =
            new CatedraCancellation(8251L, "process-a92b47d0", "CANCELLED", NOW);

    @Mock
    private CatedraAppointmentClient catedraClient;

    @Mock
    private ReservationReconciler reconciler;

    private final InMemoryReservationProcessRepository repository = new InMemoryReservationProcessRepository();

    private CancelReservationUseCaseImpl useCase() {
        return new CancelReservationUseCaseImpl(repository, catedraClient, reconciler,
                new ReservationTransitions(repository), CLOCK);
    }

    private ReservationProcess processIn(long userId, ReservationStatus status) {
        ReservationProcess created = repository.create(ReservationProcess.start(userId, "user-" + userId, 15L, "Ana",
                "Gomez", LocalDate.of(2026, 12, 7), LocalTime.of(9, 30), LocalTime.of(10, 0), "Juan", "Perez", NOW));
        repository.modify(created.getId(), p -> {
            p.setCatedraProcessId("process-a92b47d0");
            p.setHoldId("hold-6c0c2f41");
            p.setRequestEventId("request-event-8a31");
            p.setExpiresAt(NOW.plusSeconds(600));
            p.setStatus(status);
            if (status == ReservationStatus.CONFIRMED || status == ReservationStatus.CANCELLED) {
                p.setReservationId(8251L);
            }
        });
        return repository.get(created.getId());
    }

    // ---------- camino feliz ----------

    @Test
    @DisplayName("Cancela una reserva confirmada: llama a la catedra con su proceso y el motivo, y pasa a CANCELLED")
    void shouldCancelAConfirmedReservation() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);
        when(catedraClient.cancelAppointment("process-a92b47d0", "Cambio solicitado")).thenReturn(CANCELLATION);

        ReservationProcess result = useCase().cancel(USER, process.getId(), "  Cambio solicitado ");

        assertEquals(ReservationStatus.CANCELLED, result.getStatus());
        assertEquals("Cambio solicitado", result.getStatusReason());
        assertEquals(NOW, result.getCancelledAt());
        assertEquals(ReservationStatus.CANCELLED, repository.get(process.getId()).getStatus());
    }

    @Test
    @DisplayName("Sin motivo (o en blanco), no se envia ninguno")
    void shouldSendNoReasonWhenItIsBlank() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);
        when(catedraClient.cancelAppointment(anyString(), isNull())).thenReturn(CANCELLATION);

        ReservationProcess result = useCase().cancel(USER, process.getId(), "   ");

        assertEquals(ReservationStatus.CANCELLED, result.getStatus());
        assertNull(result.getStatusReason());
    }

    @Test
    @DisplayName("Un motivo de mas de 500 caracteres se rechaza antes de llamar a nadie")
    void shouldRejectATooLongReason() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> useCase().cancel(USER, process.getId(), "x".repeat(501)));

        assertEquals(true, ex.getFieldErrors().containsKey("reason"));
        verifyNoInteractions(catedraClient);
    }

    // ---------- idempotencia y estados ----------

    @Test
    @DisplayName("Una reserva ya cancelada devuelve su estado sin llamar a la catedra")
    void shouldBeIdempotentOnAnAlreadyCancelledReservation() {
        ReservationProcess process = processIn(USER, ReservationStatus.CANCELLED);

        ReservationProcess result = useCase().cancel(USER, process.getId(), null);

        assertEquals(ReservationStatus.CANCELLED, result.getStatus());
        verifyNoInteractions(catedraClient);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"STARTED", "HELD", "WAITING_FOR_PHONE", "PHONE_REQUESTED",
            "PHONE_SUBMITTED", "PHONE_REJECTED", "EXPIRED", "INVALID", "FAILED"})
    @DisplayName("Solo se cancela una reserva confirmada: en cualquier otro estado, conflicto de estado")
    void shouldOnlyCancelConfirmedReservations(ReservationStatus status) {
        ReservationProcess process = processIn(USER, status);

        assertThrows(InvalidStateTransitionException.class, () -> useCase().cancel(USER, process.getId(), null));

        assertEquals(status, repository.get(process.getId()).getStatus());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("La reserva de otro usuario o inexistente: la misma respuesta, sin llamar a la catedra")
    void shouldNotRevealReservationsOfOtherUsers() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);

        assertThrows(ReservationNotFoundException.class, () -> useCase().cancel(OTHER_USER, process.getId(), null));
        assertThrows(ReservationNotFoundException.class, () -> useCase().cancel(USER, UUID.randomUUID(), null));

        assertEquals(ReservationStatus.CONFIRMED, repository.get(process.getId()).getStatus());
        verifyNoInteractions(catedraClient);
    }

    // ---------- resultado incierto ----------

    @Test
    @DisplayName("Si la llamada no se completa, el proceso sigue CONFIRMED y repetir funciona (la cancelacion es idempotente)")
    void shouldStayConfirmedWhenTheCallFailsAndAllowARetry() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);
        when(catedraClient.cancelAppointment(anyString(), any()))
                .thenThrow(new CatedraUnavailableException("timeout de lectura", true, null))
                .thenReturn(CANCELLATION);

        assertThrows(CatedraUnavailableException.class, () -> useCase().cancel(USER, process.getId(), null));
        assertEquals(ReservationStatus.CONFIRMED, repository.get(process.getId()).getStatus());

        ReservationProcess retried = useCase().cancel(USER, process.getId(), null);

        assertEquals(ReservationStatus.CANCELLED, retried.getStatus());
        verify(catedraClient, times(2)).cancelAppointment(anyString(), any());
    }

    // ---------- rechazos de la catedra ----------

    @Test
    @DisplayName("RESERVATION_NOT_CANCELLABLE y la catedra ya la tenia cancelada: converge y devuelve CANCELLED")
    void shouldConvergeWhenTheCatedraAlreadyHadItCancelled() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);
        when(catedraClient.cancelAppointment(anyString(), any()))
                .thenThrow(new CatedraRejectedException(409, "RESERVATION_NOT_CANCELLABLE", "x"));
        ReservationProcess reconciled = repository.get(process.getId());
        reconciled.setStatus(ReservationStatus.CANCELLED);
        when(reconciler.reconcile(any())).thenReturn(reconciled);

        ReservationProcess result = useCase().cancel(USER, process.getId(), null);

        assertEquals(ReservationStatus.CANCELLED, result.getStatus());
    }

    @Test
    @DisplayName("RESERVATION_NOT_CANCELLABLE y la catedra no la tiene cancelada: 409 y el estado no cambia")
    void shouldReportNotCancellableWhenTheCatedraDoesNotHaveItCancelled() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);
        when(catedraClient.cancelAppointment(anyString(), any()))
                .thenThrow(new CatedraRejectedException(409, "RESERVATION_NOT_CANCELLABLE", "x"));
        when(reconciler.reconcile(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(ReservationNotCancellableException.class,
                () -> useCase().cancel(USER, process.getId(), null));

        assertEquals(ReservationStatus.CONFIRMED, repository.get(process.getId()).getStatus());
    }

    @Test
    @DisplayName("Otros rechazos (por ejemplo, el propietario) se propagan y no cambian el estado")
    void shouldPropagateOtherRejections() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);
        when(catedraClient.cancelAppointment(anyString(), any()))
                .thenThrow(new CatedraRejectedException(403, "RESERVATION_OWNER_MISMATCH", "x"));

        assertThrows(CatedraRejectedException.class, () -> useCase().cancel(USER, process.getId(), null));

        assertEquals(ReservationStatus.CONFIRMED, repository.get(process.getId()).getStatus());
        verifyNoInteractions(reconciler);
    }

    // ---------- convergencia con los eventos ----------

    @Test
    @DisplayName("Si el evento de cancelacion llega mientras se espera la respuesta, no hay error ni se retrocede")
    void shouldConvergeWhenTheEventArrivesFirst() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);
        when(catedraClient.cancelAppointment(anyString(), any())).thenAnswer(invocation -> {
            repository.modify(process.getId(), p -> p.setStatus(ReservationStatus.CANCELLED));
            return CANCELLATION;
        });

        ReservationProcess result = useCase().cancel(USER, process.getId(), null);

        assertEquals(ReservationStatus.CANCELLED, result.getStatus());
    }

    @Test
    @DisplayName("Un conflicto de version al registrar se reintenta, sin volver a llamar a la catedra")
    void shouldRetryTheVersionConflictWithoutCallingTheCatedraAgain() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);
        when(catedraClient.cancelAppointment(anyString(), any())).thenReturn(CANCELLATION);
        AtomicBoolean conflictInjected = new AtomicBoolean(false);
        repository.beforeUpdate(id -> {
            if (conflictInjected.compareAndSet(false, true)) {
                repository.bumpVersion(id);
            }
        });

        ReservationProcess result = useCase().cancel(USER, process.getId(), null);

        assertEquals(ReservationStatus.CANCELLED, result.getStatus());
        verify(catedraClient, times(1)).cancelAppointment(anyString(), any());
    }

    @Test
    @DisplayName("Los reintentos por conflicto tienen tope")
    void shouldGiveUpAfterTheMaximumConflicts() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);
        when(catedraClient.cancelAppointment(anyString(), any())).thenReturn(CANCELLATION);
        repository.beforeUpdate(repository::bumpVersion);

        assertThrows(ConcurrentUpdateException.class, () -> useCase().cancel(USER, process.getId(), null));

        verify(catedraClient, never()).confirmHold(anyString(), anyString(), anyString(), anyString());
    }
}