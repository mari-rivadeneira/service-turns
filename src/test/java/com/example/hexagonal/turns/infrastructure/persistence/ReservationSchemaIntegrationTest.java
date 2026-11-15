package com.example.hexagonal.turns.infrastructure.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prueba el esquema directamente en SQL, contra PostgreSQL (compose activo). No es transaccional: cada sentencia se
 * confirma sola, asi un rechazo no invalida las siguientes, y los datos de prueba se borran al terminar.
 */
@SpringBootTest
class ReservationSchemaIntegrationTest {

    private static final long TEST_USER = 990_001L;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("delete from reservation_processes where user_id = ?", TEST_USER);
        jdbc.update("delete from processed_events where event_id like 'test-%'");
    }

    // ---------- helpers ----------

    private static class Row {
        UUID id = UUID.randomUUID();
        String status = "STARTED";
        String catedraProcessId;
        String holdId;
        String requestEventId;
        String phone;
        Long reservationId;
        long professionalId = 900_001L;
        LocalDate date = LocalDate.of(2026, 12, 1);
        LocalTime start = LocalTime.of(9, 0);
        LocalTime end = LocalTime.of(9, 30);

        Row status(String value) { this.status = value; return this; }
        Row process(String catedra, String hold) { this.catedraProcessId = catedra; this.holdId = hold; return this; }
        Row request(String value) { this.requestEventId = value; return this; }
        Row phone(String value) { this.phone = value; return this; }
        Row reservation(Long value) { this.reservationId = value; return this; }
        Row start(LocalTime value) { this.start = value; this.end = value.plusMinutes(30); return this; }
        Row end(LocalTime value) { this.end = value; return this; }
    }

    private Row row() {
        return new Row();
    }

    private void insert(Row r) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into reservation_processes (id, user_id, external_patient_id, professional_id, appointment_date,
                    start_time, end_time, patient_first_name, patient_last_name, patient_phone, catedra_process_id,
                    hold_id, request_event_id, reservation_id, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                r.id, TEST_USER, "user-" + TEST_USER, r.professionalId, r.date, r.start, r.end, "Juan", "Perez",
                r.phone, r.catedraProcessId, r.holdId, r.requestEventId, r.reservationId, r.status, now, now);
    }

    // ---------- tests ----------

    @Test
    @DisplayName("Un proceso recien iniciado se guarda sin datos de la catedra y con version 0")
    void shouldInsertStartedProcess() {
        Row row = row();

        insert(row);

        String status = jdbc.queryForObject("select status from reservation_processes where id = ?", String.class, row.id);
        Long version = jdbc.queryForObject("select version from reservation_processes where id = ?", Long.class, row.id);
        assertEquals("STARTED", status);
        assertEquals(0L, version.longValue());
    }

    @Test
    @DisplayName("Un estado desconocido se rechaza")
    void shouldRejectUnknownStatus() {
        assertThrows(DataIntegrityViolationException.class, () -> insert(row().status("DESCONOCIDO")));
    }

    @Test
    @DisplayName("La hora de fin debe ser posterior a la de inicio")
    void shouldRejectInvalidTimeWindow() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insert(row().start(LocalTime.of(10, 0)).end(LocalTime.of(9, 30))));
        insert(row().start(LocalTime.of(10, 0)).end(null));
    }

    @Test
    @DisplayName("Pasado STARTED el proceso necesita los identificadores de la catedra; FAILED no")
    void shouldRequireCatedraIdsBeyondStarted() {
        assertThrows(DataIntegrityViolationException.class, () -> insert(row().status("HELD")));
        assertThrows(DataIntegrityViolationException.class, () -> insert(row().status("HELD").process("p-1", null)));

        insert(row().status("HELD").process("p-1", "h-1"));
        insert(row().status("FAILED").start(LocalTime.of(11, 0)));
    }

    @Test
    @DisplayName("CONFIRMED y CANCELLED exigen el id de la reserva")
    void shouldRequireReservationIdWhenConfirmedOrCancelled() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insert(row().status("CONFIRMED").process("p-1", "h-1")));
        assertThrows(DataIntegrityViolationException.class,
                () -> insert(row().status("CANCELLED").process("p-2", "h-2")));

        insert(row().status("CONFIRMED").process("p-3", "h-3").reservation(8251L));
    }

    @Test
    @DisplayName("PHONE_SUBMITTED exige el telefono y el eventId del pedido")
    void shouldRequirePhoneAndRequestEventWhenSubmitted() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insert(row().status("PHONE_SUBMITTED").process("p-1", "h-1").request("req-1")));
        assertThrows(DataIntegrityViolationException.class,
                () -> insert(row().status("PHONE_SUBMITTED").process("p-2", "h-2").phone("2615551234")));

        insert(row().status("PHONE_SUBMITTED").process("p-3", "h-3").request("req-3").phone("2615551234"));
    }

    @Test
    @DisplayName("El id del proceso de la catedra es unico, pero puede faltar en varios procesos")
    void shouldEnforceUniqueCatedraProcessId() {
        insert(row().status("HELD").process("p-dup", "h-1"));
        assertThrows(DuplicateKeyException.class, () -> insert(row().status("HELD").process("p-dup", "h-2")));

        insert(row());
        insert(row());
    }

    @Test
    @DisplayName("El id de la reserva es unico")
    void shouldEnforceUniqueReservationId() {
        insert(row().status("CONFIRMED").process("p-1", "h-1").reservation(8251L).start(LocalTime.of(9, 0)));

        assertThrows(DuplicateKeyException.class, () -> insert(
                row().status("CONFIRMED").process("p-2", "h-2").reservation(8251L).start(LocalTime.of(10, 0))));
    }

    @Test
    @DisplayName("Nunca dos reservas confirmadas para el mismo turno")
    void shouldNotAllowTwoConfirmedReservationsForTheSameSlot() {
        insert(row().status("CONFIRMED").process("p-a", "h-a").reservation(1L));

        assertThrows(DuplicateKeyException.class,
                () -> insert(row().status("CONFIRMED").process("p-b", "h-b").reservation(2L)));
    }

    @Test
    @DisplayName("Un turno cancelado se puede volver a reservar, y los holds en curso no bloquean el turno")
    void shouldAllowReusingTheSlotAfterCancellationAndWithPendingHolds() {
        insert(row().status("CANCELLED").process("p-a", "h-a").reservation(1L));
        insert(row().status("HELD").process("p-b", "h-b"));
        insert(row().status("HELD").process("p-c", "h-c"));
        insert(row().status("CONFIRMED").process("p-d", "h-d").reservation(2L));
    }

    @Test
    @DisplayName("Un eventId se registra una sola vez")
    void shouldRegisterEachEventOnlyOnce() {
        jdbc.update("insert into processed_events (event_id) values (?)", "test-evt-1");

        assertThrows(DuplicateKeyException.class,
                () -> jdbc.update("insert into processed_events (event_id) values (?)", "test-evt-1"));
    }
}