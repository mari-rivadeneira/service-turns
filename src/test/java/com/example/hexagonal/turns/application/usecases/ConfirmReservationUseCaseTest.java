package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.application.exception.HoldExpiredException;
import com.example.hexagonal.turns.application.exception.ReservationNotFoundException;
import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;
import com.example.hexagonal.turns.domain.model.CatedraHoldConfirmation;
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
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmReservationUseCaseTest {

    private static final long USER = 42L;
    private static final long OTHER_USER = 43L;
    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-11-20T15:10:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("America/Argentina/Buenos_Aires"));

    @Mock
    private CatedraAppointmentClient catedraClient;

    private final InMemoryReservationProcessRepository repository = new InMemoryReservationProcessRepository();

    private ConfirmReservationUseCaseImpl useCase() {
        return new ConfirmReservationUseCaseImpl(catedraClient, repository, CLOCK);
    }

    private ReservationProcess heldProcess(long userId) {
        ReservationProcess process = repository.create(ReservationProcess.start(userId, "user-" + userId, 15L,
                "Ana", "Gomez", LocalDate.of(2026, 12, 7), LocalTime.of(9, 30), LocalTime.of(10, 0),
                "Juan", "Perez", NOW));
        process.holdCreated("process-a92b47d0", "hold-6c0c2f41", EXPIRES, NOW);
        return repository.update(process);
    }

    private CatedraHoldConfirmation accepted() {
        return new CatedraHoldConfirmation("process-a92b47d0", "WAITING_FOR_PHONE", EXPIRES.plusSeconds(60));
    }

    // ---------- camino feliz ----------

    @Test
    @DisplayName("Confirma el hold con los datos guardados y pasa a WAITING_FOR_PHONE")
    void shouldConfirmAndMoveToWaitingForPhone() {
        ReservationProcess held = heldProcess(USER);
        when(catedraClient.confirmHold("hold-6c0c2f41", "user-42", "Juan", "Perez")).thenReturn(accepted());

        ReservationProcess result = useCase().confirm(USER, held.getId());

        assertEquals(ReservationStatus.WAITING_FOR_PHONE, result.getStatus());
        assertEquals(EXPIRES.plusSeconds(60), result.getExpiresAt());
        assertEquals(ReservationStatus.WAITING_FOR_PHONE, repository.get(held.getId()).getStatus());
    }

    // ---------- propiedad ----------

    @Test
    @DisplayName("El proceso de otro usuario o inexistente: la misma respuesta, sin llamar a la catedra")
    void shouldNotRevealProcessesOfOtherUsers() {
        ReservationProcess held = heldProcess(USER);

        assertThrows(ReservationNotFoundException.class, () -> useCase().confirm(OTHER_USER, held.getId()));
        assertThrows(ReservationNotFoundException.class, () -> useCase().confirm(USER, UUID.randomUUID()));

        assertEquals(ReservationStatus.HELD, repository.get(held.getId()).getStatus());
        verifyNoInteractions(catedraClient);
    }

    // ---------- idempotencia y estados ----------

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"WAITING_FOR_PHONE", "PHONE_REQUESTED", "PHONE_SUBMITTED", "PHONE_REJECTED", "CONFIRMED"})
    @DisplayName("Si la confirmacion inicial ya se hizo, repetirla devuelve el estado actual sin llamar a la catedra")
    void shouldBeIdempotentOnceAlreadyConfirmed(ReservationStatus status) {
        ReservationProcess held = heldProcess(USER);
        repository.modify(held.getId(), p -> p.setStatus(status));

        ReservationProcess result = useCase().confirm(USER, held.getId());

        assertEquals(status, result.getStatus());
        verifyNoInteractions(catedraClient);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"STARTED", "FAILED", "CANCELLED", "INVALID"})
    @DisplayName("Un proceso que no tiene un hold vigente no se puede confirmar: conflicto de estado")
    void shouldRejectConfirmationInOtherStates(ReservationStatus status) {
        ReservationProcess held = heldProcess(USER);
        repository.modify(held.getId(), p -> p.setStatus(status));

        assertThrows(InvalidStateTransitionException.class, () -> useCase().confirm(USER, held.getId()));

        assertEquals(status, repository.get(held.getId()).getStatus());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Un proceso ya vencido informa que el bloqueo no esta vigente")
    void shouldReportAnExpiredProcess() {
        ReservationProcess held = heldProcess(USER);
        repository.modify(held.getId(), p -> p.setStatus(ReservationStatus.EXPIRED));

        assertThrows(HoldExpiredException.class, () -> useCase().confirm(USER, held.getId()));

        verifyNoInteractions(catedraClient);
    }

    // ---------- rechazos de la catedra ----------

    @Test
    @DisplayName("HOLD_EXPIRED de la catedra: el proceso pasa a EXPIRED y se informa el bloqueo vencido")
    void shouldMarkExpiredWhenCatedraSaysHoldExpired() {
        ReservationProcess held = heldProcess(USER);
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CatedraRejectedException(404, "HOLD_EXPIRED", "x"));

        assertThrows(HoldExpiredException.class, () -> useCase().confirm(USER, held.getId()));

        ReservationProcess stored = repository.get(held.getId());
        assertEquals(ReservationStatus.EXPIRED, stored.getStatus());
        assertEquals("HOLD_EXPIRED", stored.getStatusReason());
    }

    @Test
    @DisplayName("HOLD_NOT_FOUND de la catedra: el proceso pasa a FAILED y se informa el bloqueo no vigente")
    void shouldMarkFailedWhenCatedraDoesNotKnowTheHold() {
        ReservationProcess held = heldProcess(USER);
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CatedraRejectedException(404, "HOLD_NOT_FOUND", "x"));

        assertThrows(HoldExpiredException.class, () -> useCase().confirm(USER, held.getId()));

        assertEquals(ReservationStatus.FAILED, repository.get(held.getId()).getStatus());
    }

    @Test
    @DisplayName("RESERVATION_PROCESS_ALREADY_CONFIRMED: la respuesta de un intento anterior se perdio; converge sin error")
    void shouldConvergeWhenCatedraAlreadyConfirmed() {
        ReservationProcess held = heldProcess(USER);
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CatedraRejectedException(409, "RESERVATION_PROCESS_ALREADY_CONFIRMED", "x"));

        ReservationProcess result = useCase().confirm(USER, held.getId());

        assertEquals(ReservationStatus.WAITING_FOR_PHONE, result.getStatus());
        assertEquals(ReservationStatus.WAITING_FOR_PHONE, repository.get(held.getId()).getStatus());
    }

    @Test
    @DisplayName("Otro rechazo (por ejemplo, el propietario del hold) no cambia el estado y se propaga")
    void shouldKeepTheStateOnOtherRejections() {
        ReservationProcess held = heldProcess(USER);
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CatedraRejectedException(403, "HOLD_OWNER_MISMATCH", "x"));

        assertThrows(CatedraRejectedException.class, () -> useCase().confirm(USER, held.getId()));

        assertEquals(ReservationStatus.HELD, repository.get(held.getId()).getStatus());
    }

    // ---------- resultado incierto ----------

    @Test
    @DisplayName("Si la llamada no se completa, el proceso sigue HELD y repetir la confirmacion funciona")
    void shouldStayHeldWhenTheCallFailsAndAllowARetry() {
        ReservationProcess held = heldProcess(USER);
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CatedraUnavailableException("timeout de lectura", true, null))
                .thenReturn(accepted());

        assertThrows(CatedraUnavailableException.class, () -> useCase().confirm(USER, held.getId()));
        assertEquals(ReservationStatus.HELD, repository.get(held.getId()).getStatus());

        ReservationProcess retried = useCase().confirm(USER, held.getId());

        assertEquals(ReservationStatus.WAITING_FOR_PHONE, retried.getStatus());
        verify(catedraClient, times(2)).confirmHold("hold-6c0c2f41", "user-42", "Juan", "Perez");
    }

    // ---------- convergencia con los eventos ----------

    @Test
    @DisplayName("Si el evento de la catedra llego antes que la respuesta REST, no se retrocede")
    void shouldNotRegressWhenTheEventArrivedFirst() {
        ReservationProcess held = heldProcess(USER);
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            // Mientras se espera la respuesta REST, el consumidor de Kafka ya avanzo el proceso
            repository.modify(held.getId(), p -> p.phoneRequested("request-event-8a31", EXPIRES, NOW));
            return accepted();
        });

        ReservationProcess result = useCase().confirm(USER, held.getId());

        assertEquals(ReservationStatus.PHONE_REQUESTED, result.getStatus());
        assertEquals("request-event-8a31", repository.get(held.getId()).getRequestEventId());
    }

    @Test
    @DisplayName("Un conflicto de version se relee y se reintenta, y el resultado se aplica")
    void shouldRetryAnOptimisticConflict() {
        ReservationProcess held = heldProcess(USER);
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString())).thenReturn(accepted());
        AtomicBoolean conflictInjected = new AtomicBoolean(false);
        repository.beforeUpdate(id -> {
            if (conflictInjected.compareAndSet(false, true)) {
                repository.bumpVersion(id);
            }
        });

        ReservationProcess result = useCase().confirm(USER, held.getId());

        assertEquals(ReservationStatus.WAITING_FOR_PHONE, result.getStatus());
        verify(catedraClient, times(1)).confirmHold(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Los reintentos por conflicto tienen tope: despues se informa ConcurrentUpdateException")
    void shouldGiveUpAfterTheMaximumConflicts() {
        ReservationProcess held = heldProcess(USER);
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString())).thenReturn(accepted());
        repository.beforeUpdate(repository::bumpVersion);

        assertThrows(ConcurrentUpdateException.class, () -> useCase().confirm(USER, held.getId()));

        verify(catedraClient, times(1)).confirmHold(anyString(), anyString(), anyString(), anyString());
        verify(catedraClient, never()).cancelAppointment(anyString(), anyString());
    }
}
