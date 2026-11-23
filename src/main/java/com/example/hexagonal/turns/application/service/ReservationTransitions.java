package com.example.hexagonal.turns.application.service;

import com.example.hexagonal.turns.application.exception.ReservationNotFoundException;
import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Aplica una transicion de estado releyendo el proceso: si ya no puede pasar al estado pedido (un evento de Kafka u otra
 * llamada lo avanzo), devuelve el estado actual sin retroceder. Un conflicto de version se reintenta, con tope.
 */
@Component
public class ReservationTransitions {

    private static final Logger log = LoggerFactory.getLogger(ReservationTransitions.class);
    private static final int MAX_CONFLICT_ATTEMPTS = 3;

    private final ReservationProcessRepository repository;

    public ReservationTransitions(ReservationProcessRepository repository) {
        this.repository = repository;
    }

    public ReservationProcess apply(UUID id, long userId, ReservationStatus target,
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
