package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatedraErrorCodes;
import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.HoldExpiredException;
import com.example.hexagonal.turns.application.exception.ReservationNotFoundException;
import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;
import com.example.hexagonal.turns.domain.model.CatedraHoldConfirmation;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.ConfirmReservationUseCase;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * NO es transaccional (ver StartReservationUseCaseImpl). La confirmacion inicial es idempotente: repetirla, por un
 * timeout o una respuesta perdida, converge al mismo estado sin duplicar nada. El estado puede cambiar en paralelo (el
 * evento de la catedra puede llegar antes de que se guarde la respuesta REST): por eso se relee y no se retrocede.
 */
@Service
public class ConfirmReservationUseCaseImpl implements ConfirmReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmReservationUseCaseImpl.class);

    private static final int MAX_CONFLICT_ATTEMPTS = 3;

    /** Estados en los que la confirmacion inicial ya se hizo: repetirla no tiene efecto. */
    private static final Set<ReservationStatus> ALREADY_CONFIRMED = EnumSet.of(
            ReservationStatus.WAITING_FOR_PHONE, ReservationStatus.PHONE_REQUESTED,
            ReservationStatus.PHONE_SUBMITTED, ReservationStatus.PHONE_REJECTED, ReservationStatus.CONFIRMED);

    private final CatedraAppointmentClient catedraClient;
    private final ReservationProcessRepository repository;
    private final Clock clock;

    public ConfirmReservationUseCaseImpl(CatedraAppointmentClient catedraClient,
                                         ReservationProcessRepository repository, Clock clock) {
        this.catedraClient = catedraClient;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public ReservationProcess confirm(long userId, UUID reservationId) {
        // La propiedad se aplica en la consulta: el proceso de otro usuario es indistinguible de uno inexistente
        ReservationProcess process = repository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(ReservationNotFoundException::new);

        if (ALREADY_CONFIRMED.contains(process.getStatus())) {
            return process;
        }
        if (process.getStatus() == ReservationStatus.EXPIRED) {
            throw new HoldExpiredException();
        }
        if (process.getStatus() != ReservationStatus.HELD) {
            throw new InvalidStateTransitionException(process.getStatus(), ReservationStatus.WAITING_FOR_PHONE);
        }

        // La vigencia la decide la catedra, que es la autoridad: no se adelanta el juicio con el reloj local.
        // Si la llamada no se puede completar (CatedraUnavailableException), el proceso sigue HELD y repetir es seguro.
        CatedraHoldConfirmation confirmation;
        try {
            confirmation = catedraClient.confirmHold(process.getHoldId(), process.getExternalPatientId(),
                    process.getPatientFirstName(), process.getPatientLastName());
        } catch (CatedraRejectedException e) {
            return handleRejection(process, e);
        }

        Instant expiresAt = confirmation.expiresAt();
        ReservationProcess confirmed = applyTransition(reservationId, userId, ReservationStatus.WAITING_FOR_PHONE,
                p -> p.initialConfirmationAccepted(expiresAt, Instant.now(clock)));
        log.info("Reserva {}: confirmacion inicial aceptada, estado {}", reservationId, confirmed.getStatus());
        return confirmed;
    }

    private ReservationProcess handleRejection(ReservationProcess process, CatedraRejectedException e) {
        UUID id = process.getId();
        long userId = process.getUserId();
        String code = e.getCode();

        if (CatedraErrorCodes.RESERVATION_PROCESS_ALREADY_CONFIRMED.equals(code)) {
            // La catedra ya habia aceptado la confirmacion (por ejemplo, se perdio la respuesta de un intento anterior)
            return applyTransition(id, userId, ReservationStatus.WAITING_FOR_PHONE,
                    p -> p.initialConfirmationAccepted(null, Instant.now(clock)));
        }
        if (CatedraErrorCodes.HOLD_EXPIRED.equals(code)) {
            applyTransition(id, userId, ReservationStatus.EXPIRED, p -> p.expired(code, Instant.now(clock)));
            throw new HoldExpiredException();
        }
        if (CatedraErrorCodes.HOLD_NOT_FOUND.equals(code)) {
            applyTransition(id, userId, ReservationStatus.FAILED, p -> p.failed(code, Instant.now(clock)));
            throw new HoldExpiredException();
        }
        // Cualquier otro rechazo (propietario, paciente, autenticacion...) no es culpa del usuario ni cambia el estado
        throw e;
    }

    /**
     * Aplica una transicion releyendo el proceso: si ya no puede pasar al estado pedido (un evento o otra llamada lo
     * avanzo), devuelve el estado actual sin retroceder. Un conflicto de version se reintenta, con tope.
     */
    private ReservationProcess applyTransition(UUID id, long userId, ReservationStatus target,
                                               Consumer<ReservationProcess> change) {
        for (int attempt = 1; ; attempt++) {
            ReservationProcess current = repository.findByIdAndUserId(id, userId)
                    .orElseThrow(ReservationNotFoundException::new);
            if (!current.canTransitionTo(target)) {
                return current;
            }
            change.accept(current);
            try {
                return repository.update(current);
            } catch (ConcurrentUpdateException e) {
                if (attempt >= MAX_CONFLICT_ATTEMPTS) {
                    throw e;
                }
                log.warn("Conflicto de version en la reserva {} (intento {}/{}): se relee", id, attempt,
                        MAX_CONFLICT_ATTEMPTS);
            }
        }
    }
}