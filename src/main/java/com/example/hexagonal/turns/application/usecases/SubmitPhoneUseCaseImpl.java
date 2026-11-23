package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.HoldExpiredException;
import com.example.hexagonal.turns.application.exception.InvalidRequestException;
import com.example.hexagonal.turns.application.exception.ReservationNotFoundException;
import com.example.hexagonal.turns.application.service.ReservationTransitions;
import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;
import com.example.hexagonal.turns.domain.model.PhoneNumber;
import com.example.hexagonal.turns.domain.model.PhoneSubmission;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.SubmitPhoneUseCase;
import com.example.hexagonal.turns.domain.port.out.PhoneSubmissionPublisher;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * NO es transaccional (ver StartReservationUseCaseImpl). Se publica PRIMERO y se registra DESPUES:
 * - si falla la publicacion, no se guardo nada y el usuario puede reintentar sin riesgo;
 * - si se publico pero falla el registro, el proceso sigue en PHONE_REQUESTED y converge cuando llega AppointmentConfirmed
 *   (la maquina de estados admite ese salto); la catedra tolera otro envio con un eventId nuevo.
 * El telefono nunca se escribe en los logs.
 */
@Service
public class SubmitPhoneUseCaseImpl implements SubmitPhoneUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitPhoneUseCaseImpl.class);
    private static final String EVENT_ID_PREFIX = "phone-submitted-";

    private final ReservationProcessRepository repository;
    private final PhoneSubmissionPublisher publisher;
    private final ReservationTransitions transitions;
    private final Clock clock;

    public SubmitPhoneUseCaseImpl(ReservationProcessRepository repository, PhoneSubmissionPublisher publisher,
                                  ReservationTransitions transitions, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Override
    public ReservationProcess submit(long userId, UUID reservationId, String phoneNumber) {
        PhoneNumber phone = parse(phoneNumber);
        ReservationProcess process = repository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(ReservationNotFoundException::new);

        switch (process.getStatus()) {
            case PHONE_REQUESTED, PHONE_REJECTED -> {
                // La catedra ya pidio el telefono: se puede enviar
            }
            case PHONE_SUBMITTED -> {
                if (phone.value().equals(process.getPatientPhone())) {
                    return process; // ya se envio este mismo telefono: no se publica de nuevo
                }
                // Otro telefono mientras se espera el resultado: solo se puede reenviar si la catedra lo rechaza
                throw new InvalidStateTransitionException(process.getStatus(), ReservationStatus.PHONE_SUBMITTED);
            }
            case CONFIRMED -> {
                return process; // el proceso ya termino bien
            }
            case EXPIRED -> throw new HoldExpiredException();
            default -> throw new InvalidStateTransitionException(process.getStatus(),
                    ReservationStatus.PHONE_SUBMITTED);
        }

        publisher.publish(new PhoneSubmission(EVENT_ID_PREFIX + UUID.randomUUID(), process.getCatedraProcessId(),
                process.getHoldId(), process.getRequestEventId(), phone.value(), Instant.now(clock)));
        log.info("Reserva {}: telefono publicado", reservationId);

        return transitions.apply(reservationId, userId, ReservationStatus.PHONE_SUBMITTED,
                p -> p.phoneSubmitted(phone, Instant.now(clock)));
    }

    private static PhoneNumber parse(String raw) {
        try {
            return PhoneNumber.of(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(
                    Map.of("phoneNumber", "debe tener entre 7 y 15 digitos, con un + inicial opcional"));
        }
    }
}