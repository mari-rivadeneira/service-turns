package com.example.hexagonal.turns.domain.port.out;

import com.example.hexagonal.turns.domain.model.ReservationProcess;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationProcessRepository {

    /** Inserta un proceso nuevo (version 0). */
    ReservationProcess create(ReservationProcess process);

    /**
     * Guarda los cambios de un proceso ya existente, con bloqueo optimista: devuelve el proceso con su version nueva.
     *
     * @throws com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException
     *         si otra operacion lo modifico despues de leerlo
     */
    ReservationProcess update(ReservationProcess process);

    Optional<ReservationProcess> findById(UUID id);

    /** Solo lo devuelve si pertenece al usuario: el proceso de otro usuario es indistinguible de uno inexistente. */
    Optional<ReservationProcess> findByIdAndUserId(UUID id, Long userId);

    Optional<ReservationProcess> findByCatedraProcessId(String catedraProcessId);

    /** Procesos del usuario, del mas nuevo al mas viejo. */
    List<ReservationProcess> findByUserId(Long userId);

    /** Procesos en curso cuya vigencia ya termino (expiresAt <= now) y todavia pueden pasar a EXPIRED. */
    List<ReservationProcess> findExpirable(Instant now);

    /** Procesos que quedaron en STARTED desde antes del umbral: nunca recibieron respuesta de la catedra. */
    List<ReservationProcess> findStartedBefore(Instant threshold);
}
