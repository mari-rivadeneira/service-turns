package com.example.hexagonal.turns.infrastructure.kafka;

import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;
import com.example.hexagonal.turns.domain.model.AppointmentActionEvent.Type;
import com.example.hexagonal.turns.infrastructure.kafka.consumer.AppointmentActionEventParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppointmentActionEventParserTest {

    private final AppointmentActionEventParser parser = new AppointmentActionEventParser(JsonMapper.builder().build());

    private AppointmentActionEvent parse(String json) {
        Optional<AppointmentActionEvent> event = parser.parse(json);
        assertTrue(event.isPresent(), "se esperaba un evento valido");
        return event.get();
    }

    @Test
    @DisplayName("AdditionalInformationRequested: conserva el eventId, el vencimiento y el proceso")
    void shouldParseRequested() {
        AppointmentActionEvent event = parse("""
                {"eventId": "request-event-8a31", "eventType": "AdditionalInformationRequested",
                 "reservationProcessId": "process-a92b47d0", "holdId": "hold-6c0c2f41",
                 "requiredField": "PHONE_NUMBER", "message": "Ingrese un numero de telefono para continuar.",
                 "expiresAt": "2026-09-10T14:44:50Z", "occurredAt": "2026-09-10T14:35:10Z", "schemaVersion": 1}
                """);

        assertEquals(Type.REQUESTED, event.type());
        assertEquals("request-event-8a31", event.eventId());
        assertEquals("process-a92b47d0", event.reservationProcessId());
        assertEquals("hold-6c0c2f41", event.holdId());
        assertEquals(Instant.parse("2026-09-10T14:44:50Z"), event.expiresAt());
    }

    @Test
    @DisplayName("AppointmentConfirmed: lee el id de la reserva")
    void shouldParseConfirmed() {
        AppointmentActionEvent event = parse("""
                {"eventId": "confirmation-event-7d10", "eventType": "AppointmentConfirmed",
                 "groupId": "proyecto-juan-perez", "reservationProcessId": "process-a92b47d0",
                 "holdId": "hold-6c0c2f41", "requestEventId": "request-event-8a31", "reservationId": 8251,
                 "professionalId": 15, "date": "2026-09-14", "startTime": "09:30", "status": "CONFIRMED",
                 "occurredAt": "2026-09-10T14:36:01Z", "schemaVersion": 1}
                """);

        assertEquals(Type.CONFIRMED, event.type());
        assertEquals(8251L, event.reservationId());
        assertEquals("request-event-8a31", event.requestEventId());
    }

    @Test
    @DisplayName("AdditionalInformationRejected: lee el motivo y el nuevo vencimiento")
    void shouldParseRejected() {
        AppointmentActionEvent event = parse("""
                {"eventId": "rejection-event-7d11", "eventType": "AdditionalInformationRejected",
                 "reservationProcessId": "process-a92b47d0", "holdId": "hold-6c0c2f41",
                 "requestEventId": "request-event-8a31", "reason": "INVALID_PHONE_NUMBER",
                 "message": "El telefono ingresado no es valido.", "expiresAt": "2026-09-10T14:44:50Z",
                 "occurredAt": "2026-09-10T14:36:01Z", "schemaVersion": 1}
                """);

        assertEquals(Type.REJECTED, event.type());
        assertEquals("INVALID_PHONE_NUMBER", event.reason());
        assertEquals(Instant.parse("2026-09-10T14:44:50Z"), event.expiresAt());
    }

    @Test
    @DisplayName("AppointmentProcessExpired y AppointmentProcessInvalid")
    void shouldParseExpiredAndInvalid() {
        AppointmentActionEvent expired = parse("""
                {"eventId": "process-event-7d12", "eventType": "AppointmentProcessExpired",
                 "reservationProcessId": "process-a92b47d0", "reason": "HOLD_EXPIRED", "schemaVersion": 1}
                """);
        AppointmentActionEvent invalid = parse("""
                {"eventId": "process-event-7d13", "eventType": "AppointmentProcessInvalid",
                 "reservationProcessId": "process-a92b47d0", "reason": "REQUEST_EVENT_MISMATCH", "schemaVersion": 1}
                """);

        assertEquals(Type.EXPIRED, expired.type());
        assertEquals("HOLD_EXPIRED", expired.reason());
        assertEquals(Type.INVALID, invalid.type());
        assertEquals("REQUEST_EVENT_MISMATCH", invalid.reason());
    }

    @Test
    @DisplayName("AppointmentCancelled: el motivo puede ser null")
    void shouldParseCancelledWithNullReason() {
        AppointmentActionEvent event = parse("""
                {"eventId": "cancellation-event-7d14", "eventType": "AppointmentCancelled",
                 "reservationProcessId": "process-a92b47d0", "reservationId": 8251, "status": "CANCELLED",
                 "reason": null, "schemaVersion": 1}
                """);

        assertEquals(Type.CANCELLED, event.type());
        assertNull(event.reason());
    }

    @Test
    @DisplayName("Los campos desconocidos se ignoran (el contrato permite agregarlos)")
    void shouldIgnoreUnknownFields() {
        AppointmentActionEvent event = parse("""
                {"eventId": "request-event-1", "eventType": "AdditionalInformationRequested",
                 "reservationProcessId": "process-1", "campoNuevo": {"a": 1}, "otroCampo": [1, 2]}
                """);

        assertEquals("request-event-1", event.eventId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "esto no es json", "[]", "null", "{\"eventType\": \"AppointmentConfirmed\"}"})
    @DisplayName("Un mensaje vacio, ilegible o sin eventId se descarta")
    void shouldDiscardUnreadableMessages(String payload) {
        assertTrue(parser.parse(payload).isEmpty());
    }

    @Test
    @DisplayName("Sin reservationProcessId, con un tipo desconocido o con otra version de esquema, se descarta")
    void shouldDiscardIncompleteOrIncompatibleEvents() {
        assertTrue(parser.parse("{\"eventId\": \"e1\", \"eventType\": \"AppointmentConfirmed\", \"reservationId\": 1}").isEmpty());
        assertTrue(parser.parse("""
                {"eventId": "e2", "eventType": "AlgoNuevo", "reservationProcessId": "p1"}
                """).isEmpty());
        assertTrue(parser.parse("""
                {"eventId": "e3", "eventType": "AppointmentProcessExpired", "reservationProcessId": "p1",
                 "schemaVersion": 2}
                """).isEmpty());
        assertTrue(parser.parse("""
                {"eventId": "e4", "reservationProcessId": "p1"}
                """).isEmpty());
    }

    @Test
    @DisplayName("Una confirmacion sin reservationId se descarta")
    void shouldDiscardAConfirmationWithoutReservationId() {
        assertTrue(parser.parse("""
                {"eventId": "e5", "eventType": "AppointmentConfirmed", "reservationProcessId": "p1"}
                """).isEmpty());
    }
}
