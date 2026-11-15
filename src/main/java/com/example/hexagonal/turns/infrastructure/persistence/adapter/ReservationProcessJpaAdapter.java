package com.example.hexagonal.turns.infrastructure.persistence.adapter;

import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import com.example.hexagonal.turns.infrastructure.persistence.entity.ReservationProcessEntity;
import com.example.hexagonal.turns.infrastructure.persistence.mapper.ReservationProcessMapper;
import com.example.hexagonal.turns.infrastructure.persistence.repository.SpringDataReservationProcessRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationProcessJpaAdapter implements ReservationProcessRepository {

    /** Los estados que pueden vencer salen de la maquina de estados: si esta cambia, esta consulta la sigue. */
    private static final List<ReservationStatus> EXPIRABLE = Arrays.stream(ReservationStatus.values())
            .filter(status -> status.canTransitionTo(ReservationStatus.EXPIRED))
            .toList();

    @PersistenceContext
    private EntityManager entityManager;

    private final SpringDataReservationProcessRepository repository;
    private final ReservationProcessMapper mapper;

    public ReservationProcessJpaAdapter(SpringDataReservationProcessRepository repository,
                                        ReservationProcessMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public ReservationProcess create(ReservationProcess process) {
        ReservationProcessEntity entity = mapper.toNewEntity(process);
        entityManager.persist(entity);
        entityManager.flush();
        return mapper.toDomain(entity);
    }

    @Override
    @Transactional
    public ReservationProcess update(ReservationProcess process) {
        try {
            // saveAndFlush: el conflicto de version tiene que verse aca y no recien al cerrar una transaccion externa
            return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(process)));
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentUpdateException(process.getId(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReservationProcess> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReservationProcess> findByIdAndUserId(UUID id, Long userId) {
        return repository.findByIdAndUserId(id, userId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReservationProcess> findByCatedraProcessId(String catedraProcessId) {
        return repository.findByCatedraProcessId(catedraProcessId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationProcess> findByUserId(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationProcess> findExpirable(Instant now) {
        return repository.findExpirable(EXPIRABLE, now).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationProcess> findStartedBefore(Instant threshold) {
        return repository.findByStatusAndCreatedAtBefore(ReservationStatus.STARTED, threshold).stream()
                .map(mapper::toDomain)
                .toList();
    }
}