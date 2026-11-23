package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.ApplyAppointmentActionUseCase;
import com.example.hexagonal.turns.domain.port.out.ProcessedEventRepository;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Es transaccional: registrar el eventId y cambiar el estado se confirman juntos o ninguno. Si el cambio falla, el registro
 * del evento se deshace y el evento se podra reprocesar.
 */
@Service
public class ApplyAppointmentActionUseCaseImpl implements ApplyAppointmentActionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyAppointmentActionUseCaseImpl.class);
    private static final String DEFAULT_EXPIRATION_REASON = "HOLD_EXPIRED";

    private final ReservationProcessRepository repository;
    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    public ApplyAppointmentActionUseCaseImpl(ReservationProcessRepository repository,
                                             ProcessedEventRepository processedEvents, Clock clock) {
        this.repository = repository;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Result apply(AppointmentActionEvent event) {
        Optional<ReservationProcess> found = repository.findByCatedraProcessId(event.reservationProcessId());
        if (found.isEmpty()) {
            // Sin proceso no hay a que aplicarlo: no se registra, asi no se pierde si despues aparece
            log.warn("Evento {} ({}) de un proceso desconocido: se descarta", event.eventId(), event.type());
            return Result.UNKNOWN_PROCESS;
        }

        // Atomico y sin excepcion si ya estaba. Si el cambio de abajo falla, este registro se deshace con la transaccion.
        if (!processedEvents.markProcessed(event.eventId())) {
            log.info("Evento {} ya procesado: se ignora", event.eventId());
            return Result.DUPLICATE;
        }

        ReservationProcess process = found.get();
        if (!process.canTransitionTo(targetOf(event.type()))) {
            // Tardio o repetido: un estado final no se reabre ni una reserva confirmada vence. Queda registrado.
            log.info("Evento {} ({}) no corresponde al estado {} de la reserva {}: se ignora", event.eventId(),
                    event.type(), process.getStatus(), process.getId());
            return Result.NOT_APPLICABLE;
        }

        change(process, event, Instant.now(clock));
        repository.update(process);
        log.info("Reserva {}: evento {} aplicado, estado {}", process.getId(), event.type(), process.getStatus());
        return Result.APPLIED;
    }

    private static ReservationStatus targetOf(AppointmentActionEvent.Type type) {
        return switch (type) {
            case REQUESTED -> ReservationStatus.PHONE_REQUESTED;
            case CONFIRMED -> ReservationStatus.CONFIRMED;
            case REJECTED -> ReservationStatus.PHONE_REJECTED;
            case EXPIRED -> ReservationStatus.EXPIRED;
            case INVALID -> ReservationStatus.INVALID;
            case CANCELLED -> ReservationStatus.CANCELLED;
        };
    }

    private static void change(ReservationProcess process, AppointmentActionEvent event, Instant now) {
        switch (event.type()) {
            // Se conserva el eventId de la solicitud: hay que devolverlo como requestEventId al enviar el telefono
            case REQUESTED -> process.phoneRequested(event.eventId(), event.expiresAt(), now);
            case CONFIRMED -> process.confirmed(event.reservationId(), now);
            case REJECTED -> process.phoneRejected(event.reason(), event.expiresAt(), now);
            case EXPIRED -> process.expired(event.reason() != null ? event.reason() : DEFAULT_EXPIRATION_REASON, now);
            case INVALID -> process.invalidated(event.reason(), now);
            case CANCELLED -> process.cancelled(event.reason(), now);
        }
    }
}