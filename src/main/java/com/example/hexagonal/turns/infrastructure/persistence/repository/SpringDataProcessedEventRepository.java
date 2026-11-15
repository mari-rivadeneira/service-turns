package com.example.hexagonal.turns.infrastructure.persistence.repository;

import com.example.hexagonal.turns.infrastructure.persistence.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {

    /** Atomico y sin excepcion si ya existia: devuelve 1 si lo inserto y 0 si ya estaba. */
    @Modifying
    @Query(value = "insert into processed_events (event_id, processed_at) values (:eventId, now()) "
            + "on conflict (event_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId);
}
