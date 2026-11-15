package com.example.hexagonal.turns.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Solo lectura desde JPA: el registro se inserta con una sentencia nativa atomica (ver el repositorio). */
@Entity
@Table(name = "processed_events")
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", length = 200)
    private String eventId;

    @Column(name = "processed_at", nullable = false, insertable = false, updatable = false)
    private Instant processedAt;

    public ProcessedEventEntity() {
    }

    public String getEventId() { return eventId; }
    public Instant getProcessedAt() { return processedAt; }
}