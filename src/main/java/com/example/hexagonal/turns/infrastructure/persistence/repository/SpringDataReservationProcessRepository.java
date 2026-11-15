package com.example.hexagonal.turns.infrastructure.persistence.repository;

import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.infrastructure.persistence.entity.ReservationProcessEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataReservationProcessRepository extends JpaRepository<ReservationProcessEntity, UUID> {

    Optional<ReservationProcessEntity> findByIdAndUserId(UUID id, Long userId);

    Optional<ReservationProcessEntity> findByCatedraProcessId(String catedraProcessId);

    List<ReservationProcessEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("select p from ReservationProcessEntity p where p.status in :statuses and p.expiresAt <= :now "
            + "order by p.expiresAt asc")
    List<ReservationProcessEntity> findExpirable(@Param("statuses") Collection<ReservationStatus> statuses,
                                                 @Param("now") Instant now);

    List<ReservationProcessEntity> findByStatusAndCreatedAtBefore(ReservationStatus status, Instant threshold);
}
