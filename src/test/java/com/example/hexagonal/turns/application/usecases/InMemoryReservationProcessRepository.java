package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import com.example.hexagonal.turns.infrastructure.persistence.mapper.ReservationProcessMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Repositorio en memoria para probar los casos de uso: entrega copias y respeta el bloqueo optimista. */
public class InMemoryReservationProcessRepository implements ReservationProcessRepository {

    private final Map<UUID, ReservationProcess> store = new LinkedHashMap<>();
    private final ReservationProcessMapper mapper = new ReservationProcessMapper();
    private Consumer<UUID> beforeUpdate = id -> { };
    private boolean failOnUpdate;

    private ReservationProcess copy(ReservationProcess process) {
        return mapper.toDomain(mapper.toEntity(process));
    }

    // ---------- puerto ----------

    @Override
    public ReservationProcess create(ReservationProcess process) {
        ReservationProcess stored = copy(process);
        stored.setVersion(0L);
        store.put(stored.getId(), stored);
        return copy(stored);
    }

    @Override
    public ReservationProcess update(ReservationProcess process) {
        if (failOnUpdate) {
            throw new IllegalStateException("base de datos caida");
        }
        beforeUpdate.accept(process.getId());
        ReservationProcess current = store.get(process.getId());
        if (current == null || current.getVersion() != process.getVersion()) {
            throw new ConcurrentUpdateException(process.getId(), null);
        }
        ReservationProcess stored = copy(process);
        stored.setVersion(process.getVersion() + 1);
        store.put(stored.getId(), stored);
        return copy(stored);
    }

    @Override
    public Optional<ReservationProcess> findById(UUID id) {
        return Optional.ofNullable(store.get(id)).map(this::copy);
    }

    @Override
    public Optional<ReservationProcess> findByIdAndUserId(UUID id, Long userId) {
        return findById(id).filter(process -> process.isOwnedBy(userId));
    }

    @Override
    public Optional<ReservationProcess> findByCatedraProcessId(String catedraProcessId) {
        return store.values().stream()
                .filter(process -> catedraProcessId.equals(process.getCatedraProcessId()))
                .findFirst().map(this::copy);
    }

    @Override
    public List<ReservationProcess> findByUserId(Long userId) {
        return store.values().stream()
                .filter(process -> process.isOwnedBy(userId))
                .sorted(Comparator.comparing(ReservationProcess::getCreatedAt).reversed())
                .map(this::copy).toList();
    }

    @Override
    public List<ReservationProcess> findExpirable(Instant now) {
        return store.values().stream().filter(process -> process.canExpire(now)).map(this::copy).toList();
    }

    @Override
    public List<ReservationProcess> findStartedBefore(Instant threshold) {
        return store.values().stream()
                .filter(process -> process.getStatus() == ReservationStatus.STARTED
                        && process.getCreatedAt().isBefore(threshold))
                .map(this::copy).toList();
    }

    @Override
    public Optional<ReservationProcess> findActiveByUserAndSlot(Long userId, Long professionalId, LocalDate date,
                                                                LocalTime startTime) {
        return store.values().stream()
                .filter(process -> process.isOwnedBy(userId)
                        && process.getProfessionalId().equals(professionalId)
                        && process.getAppointmentDate().equals(date)
                        && process.getStartTime().equals(startTime)
                        && !process.getStatus().isFinal())
                .max(Comparator.comparing(ReservationProcess::getCreatedAt))
                .map(this::copy);
    }

    // ---------- ayudas para los tests ----------

    public List<ReservationProcess> all() {
        return store.values().stream().map(this::copy).toList();
    }

    public ReservationProcess get(UUID id) {
        return copy(store.get(id));
    }

    public int count() {
        return store.size();
    }

    /** Simula que otro escritor (por ejemplo, un evento de Kafka) modifico el proceso: sube la version. */
    public void modify(UUID id, Consumer<ReservationProcess> change) {
        ReservationProcess current = store.get(id);
        change.accept(current);
        current.setVersion(current.getVersion() + 1);
    }

    public void bumpVersion(UUID id) {
        ReservationProcess current = store.get(id);
        current.setVersion(current.getVersion() + 1);
    }

    /** Se ejecuta antes de comprobar la version en cada update: sirve para simular un conflicto. */
    public void beforeUpdate(Consumer<UUID> hook) {
        this.beforeUpdate = hook;
    }

    public void failOnUpdate(boolean fail) {
        this.failOnUpdate = fail;
    }
}
