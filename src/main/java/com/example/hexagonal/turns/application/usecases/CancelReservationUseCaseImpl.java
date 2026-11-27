package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatedraErrorCodes;
import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.InvalidRequestException;
import com.example.hexagonal.turns.application.exception.ReservationNotCancellableException;
import com.example.hexagonal.turns.application.exception.ReservationNotFoundException;
import com.example.hexagonal.turns.application.service.ReservationReconciler;
import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.CancelReservationUseCase;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import com.example.hexagonal.turns.application.service.ReservationTransitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * NO es transaccional (ver StartReservationUseCaseImpl). La cancelacion es idempotente en la catedra, asi que a diferencia
 * de la creacion del hold, ante un resultado incierto repetir es seguro: el proceso sigue CONFIRMED hasta que la catedra
 * lo confirme, ya sea por la respuesta, por el evento AppointmentCancelled o por una reconciliacion.
 */
@Service
public class CancelReservationUseCaseImpl implements CancelReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelReservationUseCaseImpl.class);
    private static final int MAX_REASON_LENGTH = 500;

    private final ReservationProcessRepository repository;
    private final CatedraAppointmentClient catedraClient;
    private final ReservationReconciler reconciler;
    private final ReservationTransitions transitions;
    private final Clock clock;

    public CancelReservationUseCaseImpl(ReservationProcessRepository repository,
                                        CatedraAppointmentClient catedraClient, ReservationReconciler reconciler,
                                        ReservationTransitions transitions, Clock clock) {
        this.repository = repository;
        this.catedraClient = catedraClient;
        this.reconciler = reconciler;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Override
    public ReservationProcess cancel(long userId, UUID reservationId, String reason) {
        String cleanReason = cleanReason(reason);
        // La propiedad se aplica en la consulta: la reserva de otro usuario es indistinguible de una inexistente
        ReservationProcess process = repository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(ReservationNotFoundException::new);

        switch (process.getStatus()) {
            case CONFIRMED -> {
                // Es la unica que se puede cancelar
            }
            case CANCELLED -> {
                return process; // idempotente
            }
            default -> throw new InvalidStateTransitionException(process.getStatus(), ReservationStatus.CANCELLED);
        }

        try {
            catedraClient.cancelAppointment(process.getCatedraProcessId(), cleanReason);
        } catch (CatedraRejectedException e) {
            return handleRejection(process, e);
        }
        // CatedraUnavailableException se propaga: el proceso sigue CONFIRMED y repetir es seguro

        ReservationProcess cancelled = transitions.apply(reservationId, userId, ReservationStatus.CANCELLED,
                p -> p.cancelled(cleanReason, Instant.now(clock)));
        log.info("Reserva {} cancelada", reservationId);
        return cancelled;
    }

    private ReservationProcess handleRejection(ReservationProcess process, CatedraRejectedException e) {
        if (CatedraErrorCodes.RESERVATION_NOT_CANCELLABLE.equals(e.getCode())) {
            // El estado en la catedra no coincide con el nuestro: se consulta el real y se converge
            ReservationProcess reconciled = reconciler.reconcile(process);
            if (reconciled.getStatus() == ReservationStatus.CANCELLED) {
                return reconciled; // ya estaba cancelada: se perdio el evento
            }
            throw new ReservationNotCancellableException();
        }
        throw e; // otros rechazos (propietario, autenticacion...) no son culpa del usuario ni cambian el estado
    }

    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new InvalidRequestException(Map.of("reason", "no puede superar los " + MAX_REASON_LENGTH + " caracteres"));
        }
        return trimmed;
    }
}
