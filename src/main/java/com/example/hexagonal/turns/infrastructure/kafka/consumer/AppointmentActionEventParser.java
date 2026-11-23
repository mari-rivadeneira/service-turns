package com.example.hexagonal.turns.infrastructure.kafka.consumer;

import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

/**
 * Interpreta los mensajes del topic de acciones. Ignora los campos desconocidos (el contrato permite agregarlos). Un mensaje
 * ilegible, incompleto o de un tipo desconocido devuelve vacio: reintentarlo no lo arregla. No registra el contenido.
 */
@Component
public class AppointmentActionEventParser {

    private static final Logger log = LoggerFactory.getLogger(AppointmentActionEventParser.class);
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public AppointmentActionEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<AppointmentActionEvent> parse(String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("Mensaje vacio");
            return Optional.empty();
        }

        RawEvent raw;
        try {
            raw = objectMapper.readValue(payload, RawEvent.class);
        } catch (RuntimeException e) {
            log.warn("Mensaje ilegible: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (raw == null || isBlank(raw.eventId()) || isBlank(raw.reservationProcessId())) {
            log.warn("Mensaje sin eventId o sin reservationProcessId");
            return Optional.empty();
        }
        if (raw.schemaVersion() != null && raw.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            log.warn("Evento {} con una version de esquema no soportada: {}", raw.eventId(), raw.schemaVersion());
            return Optional.empty();
        }

        AppointmentActionEvent.Type type = typeOf(raw.eventType());
        if (type == null) {
            log.warn("Evento {} de un tipo desconocido: {}", raw.eventId(), raw.eventType());
            return Optional.empty();
        }
        if (type == AppointmentActionEvent.Type.CONFIRMED && raw.reservationId() == null) {
            log.warn("Evento {} de confirmacion sin reservationId", raw.eventId());
            return Optional.empty();
        }

        return Optional.of(new AppointmentActionEvent(raw.eventId(), type, raw.reservationProcessId(), raw.holdId(),
                raw.requestEventId(), raw.reservationId(), raw.reason(), raw.expiresAt()));
    }

    private static AppointmentActionEvent.Type typeOf(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case "AdditionalInformationRequested" -> AppointmentActionEvent.Type.REQUESTED;
            case "AppointmentConfirmed" -> AppointmentActionEvent.Type.CONFIRMED;
            case "AdditionalInformationRejected" -> AppointmentActionEvent.Type.REJECTED;
            case "AppointmentProcessExpired" -> AppointmentActionEvent.Type.EXPIRED;
            case "AppointmentProcessInvalid" -> AppointmentActionEvent.Type.INVALID;
            case "AppointmentCancelled" -> AppointmentActionEvent.Type.CANCELLED;
            default -> null;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawEvent(String eventId, String eventType, String reservationProcessId, String holdId,
                    String requestEventId, Long reservationId, String reason, Instant expiresAt,
                    Integer schemaVersion) {
    }
}