package com.example.hexagonal.turns.application.service;

import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.model.AppointmentQuery;
import com.example.hexagonal.turns.domain.model.CatedraAppointment;
import com.example.hexagonal.turns.domain.model.CatedraAppointmentPage;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Reconcilia un proceso con el estado real que informa la catedra (GET /api/appointments). La catedra es la autoridad: si se
 * perdio un evento de Kafka, esta consulta lo recupera. Nunca retrocede un estado: solo aplica transiciones permitidas.
 * La usan el endpoint de refresco, la cancelacion y el job de recuperacion.
 */
@Component
public class ReservationReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReservationReconciler.class);
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 3;
    private static final int MAX_CONFLICT_ATTEMPTS = 3;
    private static final String HOLD_EXPIRED = "HOLD_EXPIRED";
    private static final String CATEDRA_FAILED = "CATEDRA_FAILED";

    private final ReservationProcessRepository repository;
    private final CatedraAppointmentClient catedraClient;
    private final Clock clock;

    public ReservationReconciler(ReservationProcessRepository repository, CatedraAppointmentClient catedraClient,
                                 Clock clock) {
        this.repository = repository;
        this.catedraClient = catedraClient;
        this.clock = clock;
    }

    /**
     * @throws com.example.hexagonal.turns.application.exception.CatedraUnavailableException si la catedra no responde
     *         (es una consulta: repetirla es seguro)
     */
    public ReservationProcess reconcile(ReservationProcess process) {
        // Sin datos de la catedra (todavia STARTED) o ya en un estado final: no hay nada que preguntar
        if (process.getCatedraProcessId() == null || process.isFinal()) {
            return process;
        }

        Optional<CatedraAppointment> found = findAppointment(process);

        for (int attempt = 1; ; attempt++) {
            // Se relee el proceso: un evento o un pedido pudieron cambiarlo mientras se consultaba a la catedra
            ReservationProcess current = repository.findById(process.getId()).orElse(process);
            if (current.isFinal() || !apply(current, found, Instant.now(clock))) {
                return current;
            }
            try {
                ReservationProcess updated = repository.update(current);
                log.info("Reserva {} reconciliada con la catedra: estado {}", updated.getId(), updated.getStatus());
                return updated;
            } catch (ConcurrentUpdateException e) {
                if (attempt >= MAX_CONFLICT_ATTEMPTS) {
                    throw e;
                }
                log.warn("Conflicto de version al reconciliar la reserva {} (intento {}/{}): se relee",
                        process.getId(), attempt, MAX_CONFLICT_ATTEMPTS);
            }
        }
    }

    /** Busca el proceso entre las reservas de ese paciente en la fecha del turno (pocas, normalmente una pagina). */
    private Optional<CatedraAppointment> findAppointment(ReservationProcess process) {
        for (int page = 0; page < MAX_PAGES; page++) {
            CatedraAppointmentPage result = catedraClient.listAppointments(new AppointmentQuery(null,
                    process.getAppointmentDate(), process.getAppointmentDate(), process.getExternalPatientId(),
                    page, PAGE_SIZE));
            Optional<CatedraAppointment> match = result.content().stream()
                    .filter(appointment -> process.getCatedraProcessId().equals(appointment.reservationProcessId()))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
            if (result.content().isEmpty() || (long) (page + 1) * PAGE_SIZE >= result.totalCount()) {
                break;
            }
        }
        return Optional.empty();
    }

    /** @return true si el proceso cambio de estado */
    private boolean apply(ReservationProcess process, Optional<CatedraAppointment> found, Instant now) {
        if (found.isEmpty()) {
            // Un hold que nunca se confirmo y ya paso su vigencia: la catedra no tiene nada que informar
            if (process.getStatus() == ReservationStatus.HELD && process.canExpire(now)) {
                process.expired(HOLD_EXPIRED, now);
                return true;
            }
            return false;
        }

        CatedraAppointment appointment = found.get();
        CatedraAppointment.Status status = appointment.status();

        if (status == CatedraAppointment.Status.CONFIRMED) {
            return confirm(process, appointment, now);
        }
        if (status == CatedraAppointment.Status.CANCELLED) {
            // Si se perdieron los dos eventos, primero se refleja la confirmacion y despues la cancelacion
            boolean changed = confirm(process, appointment, now);
            if (process.canTransitionTo(ReservationStatus.CANCELLED)) {
                process.cancelled(appointment.cancellationReason(), now);
                changed = true;
            }
            return changed;
        }
        if (status == CatedraAppointment.Status.FAILED) {
            // El REST no distingue vencida de fallida: con la vigencia cumplida se trata como vencida
            if (process.canExpire(now)) {
                process.expired(HOLD_EXPIRED, now);
                return true;
            }
            if (process.canTransitionTo(ReservationStatus.FAILED)) {
                process.failed(CATEDRA_FAILED, now);
                return true;
            }
            return false;
        }
        // PHONE_PENDING: la catedra sigue esperando el telefono. Sin el eventId del pedido no se puede avanzar.
        return false;
    }

    private boolean confirm(ReservationProcess process, CatedraAppointment appointment, Instant now) {
        if (appointment.reservationId() != null && process.canTransitionTo(ReservationStatus.CONFIRMED)) {
            process.confirmed(appointment.reservationId(), now);
            return true;
        }
        return false;
    }
}