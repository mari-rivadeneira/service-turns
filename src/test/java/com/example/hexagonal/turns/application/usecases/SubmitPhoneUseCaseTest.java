package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.HoldExpiredException;
import com.example.hexagonal.turns.application.exception.InvalidRequestException;
import com.example.hexagonal.turns.application.exception.PhonePublishException;
import com.example.hexagonal.turns.application.exception.ReservationNotFoundException;
import com.example.hexagonal.turns.application.service.ReservationTransitions;
import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;
import com.example.hexagonal.turns.domain.model.PhoneSubmission;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.out.PhoneSubmissionPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SubmitPhoneUseCaseTest {

    private static final long USER = 42L;
    private static final long OTHER_USER = 43L;
    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private PhoneSubmissionPublisher publisher;

    private final InMemoryReservationProcessRepository repository = new InMemoryReservationProcessRepository();

    private SubmitPhoneUseCaseImpl useCase() {
        return new SubmitPhoneUseCaseImpl(repository, publisher, new ReservationTransitions(repository), CLOCK);
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
        });
        return repository.get(created.getId());
    }

    // ---------- camino feliz ----------

    @Test
    @DisplayName("Publica el telefono normalizado ANTES de registrar el estado, y despues el proceso pasa a PHONE_SUBMITTED")
    void shouldPublishBeforePersisting() {
        ReservationProcess process = processIn(USER, ReservationStatus.PHONE_REQUESTED);
        doAnswer(invocation -> {
            // Mientras se publica, el estado todavia no cambio
            assertEquals(ReservationStatus.PHONE_REQUESTED, repository.get(process.getId()).getStatus());
            return null;
        }).when(publisher).publish(any());

        ReservationProcess result = useCase().submit(USER, process.getId(), "+54 (261) 555-1234");

        assertEquals(ReservationStatus.PHONE_SUBMITTED, result.getStatus());
        assertEquals("+542615551234", repository.get(process.getId()).getPatientPhone());
        ArgumentCaptor<PhoneSubmission> captor = ArgumentCaptor.forClass(PhoneSubmission.class);
        verify(publisher).publish(captor.capture());
        PhoneSubmission submission = captor.getValue();
        assertTrue(submission.eventId().startsWith("phone-submitted-"));
        assertEquals("process-a92b47d0", submission.reservationProcessId());
        assertEquals("hold-6c0c2f41", submission.holdId());
        assertEquals("request-event-8a31", submission.requestEventId());
        assertEquals("+542615551234", submission.phoneNumber());
        assertEquals(NOW, submission.occurredAt());
    }

    @Test
    @DisplayName("Tras un rechazo se puede reenviar: mismo requestEventId y un eventId nuevo")
    void shouldAllowResubmittingAfterARejection() {
        ReservationProcess process = processIn(USER, ReservationStatus.PHONE_REJECTED);

        ReservationProcess result = useCase().submit(USER, process.getId(), "2615559999");

        assertEquals(ReservationStatus.PHONE_SUBMITTED, result.getStatus());
        assertEquals("2615559999", repository.get(process.getId()).getPatientPhone());
        ArgumentCaptor<PhoneSubmission> captor = ArgumentCaptor.forClass(PhoneSubmission.class);
        verify(publisher).publish(captor.capture());
        assertEquals("request-event-8a31", captor.getValue().requestEventId());
    }

    // ---------- idempotencia y estados ----------

    @Test
    @DisplayName("Repetir el mismo telefono mientras se espera el resultado no publica de nuevo")
    void shouldNotPublishTheSamePhoneTwice() {
        ReservationProcess process = processIn(USER, ReservationStatus.PHONE_REQUESTED);
        useCase().submit(USER, process.getId(), "2615551234");

        ReservationProcess again = useCase().submit(USER, process.getId(), "261-555-1234");

        assertEquals(ReservationStatus.PHONE_SUBMITTED, again.getStatus());
        verify(publisher, times(1)).publish(any());
    }

    @Test
    @DisplayName("Otro telefono mientras se espera el resultado es un conflicto de estado")
    void shouldRejectADifferentPhoneWhileWaiting() {
        ReservationProcess process = processIn(USER, ReservationStatus.PHONE_REQUESTED);
        useCase().submit(USER, process.getId(), "2615551234");

        assertThrows(InvalidStateTransitionException.class,
                () -> useCase().submit(USER, process.getId(), "2615559999"));

        verify(publisher, times(1)).publish(any());
    }

    @Test
    @DisplayName("Si la reserva ya esta confirmada devuelve el estado actual sin publicar")
    void shouldReturnTheCurrentStateWhenAlreadyConfirmed() {
        ReservationProcess process = processIn(USER, ReservationStatus.CONFIRMED);

        ReservationProcess result = useCase().submit(USER, process.getId(), "2615551234");

        assertEquals(ReservationStatus.CONFIRMED, result.getStatus());
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Un proceso vencido informa que el bloqueo no esta vigente")
    void shouldReportAnExpiredProcess() {
        ReservationProcess process = processIn(USER, ReservationStatus.EXPIRED);

        assertThrows(HoldExpiredException.class, () -> useCase().submit(USER, process.getId(), "2615551234"));

        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"STARTED", "HELD", "WAITING_FOR_PHONE", "FAILED", "CANCELLED", "INVALID"})
    @DisplayName("Si la catedra todavia no pidio el telefono, o el proceso termino mal, es un conflicto de estado")
    void shouldRejectOtherStates(ReservationStatus status) {
        ReservationProcess process = processIn(USER, status);

        assertThrows(InvalidStateTransitionException.class,
                () -> useCase().submit(USER, process.getId(), "2615551234"));

        assertEquals(status, repository.get(process.getId()).getStatus());
        verifyNoInteractions(publisher);
    }

    // ---------- propiedad y validacion ----------

    @Test
    @DisplayName("El proceso de otro usuario o inexistente: la misma respuesta, sin publicar")
    void shouldNotRevealProcessesOfOtherUsers() {
        ReservationProcess process = processIn(USER, ReservationStatus.PHONE_REQUESTED);

        assertThrows(ReservationNotFoundException.class,
                () -> useCase().submit(OTHER_USER, process.getId(), "2615551234"));
        assertThrows(ReservationNotFoundException.class,
                () -> useCase().submit(USER, UUID.randomUUID(), "2615551234"));

        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "123", "1234567890123456", "26155+51234"})
    @DisplayName("Un telefono invalido se rechaza con el detalle del campo, antes de publicar")
    void shouldRejectInvalidPhones(String phone) {
        ReservationProcess process = processIn(USER, ReservationStatus.PHONE_REQUESTED);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> useCase().submit(USER, process.getId(), phone));

        assertTrue(ex.getFieldErrors().containsKey("phoneNumber"));
        verifyNoInteractions(publisher);
    }

    // ---------- fallos y convergencia ----------

    @Test
    @DisplayName("Si no se puede publicar, no se guarda nada y el estado no cambia")
    void shouldNotPersistWhenThePublicationFails() {
        ReservationProcess process = processIn(USER, ReservationStatus.PHONE_REQUESTED);
        doThrow(new PhonePublishException("Kafka caido", null)).when(publisher).publish(any());

        assertThrows(PhonePublishException.class, () -> useCase().submit(USER, process.getId(), "2615551234"));

        ReservationProcess stored = repository.get(process.getId());
        assertEquals(ReservationStatus.PHONE_REQUESTED, stored.getStatus());
        assertEquals(null, stored.getPatientPhone());
    }

    @Test
    @DisplayName("Si la confirmacion llega mientras se publica, no se retrocede")
    void shouldNotRegressWhenTheConfirmationArrivesWhilePublishing() {
        ReservationProcess process = processIn(USER, ReservationStatus.PHONE_REQUESTED);
        doAnswer(invocation -> {
            repository.modify(process.getId(), p -> {
                p.setStatus(ReservationStatus.CONFIRMED);
                p.setReservationId(8251L);
            });
            return null;
        }).when(publisher).publish(any());

        ReservationProcess result = useCase().submit(USER, process.getId(), "2615551234");

        assertEquals(ReservationStatus.CONFIRMED, result.getStatus());
        assertEquals(8251L, repository.get(process.getId()).getReservationId());
        verify(publisher, times(1)).publish(any());
    }

    @Test
    @DisplayName("Un conflicto de version al registrar se reintenta, sin volver a publicar")
    void shouldRetryTheVersionConflictWithoutPublishingAgain() {
        ReservationProcess process = processIn(USER, ReservationStatus.PHONE_REQUESTED);
        AtomicBoolean conflictInjected = new AtomicBoolean(false);
        repository.beforeUpdate(id -> {
            if (conflictInjected.compareAndSet(false, true)) {
                repository.bumpVersion(id);
            }
        });

        ReservationProcess result = useCase().submit(USER, process.getId(), "2615551234");

        assertEquals(ReservationStatus.PHONE_SUBMITTED, result.getStatus());
        verify(publisher, times(1)).publish(any());
        assertNotEquals(0L, repository.get(process.getId()).getVersion());
    }
}