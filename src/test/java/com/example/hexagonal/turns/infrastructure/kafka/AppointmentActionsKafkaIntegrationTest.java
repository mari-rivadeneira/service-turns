package com.example.hexagonal.turns.infrastructure.kafka;

import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Flujo completo contra el Kafka y la base del compose: se publican eventos reales y se espera su efecto. Usa topics y grupo
 * propios. Como hay una sola particion y un solo consumidor, un evento procesado implica que los anteriores ya lo fueron.
 */
@SpringBootTest
@Import(KafkaTestTopicsConfiguration.class)
class AppointmentActionsKafkaIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final long USER = 990_401L;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("catedra.kafka.appointment-actions-topic", () -> "test.actions." + SUFFIX);
        registry.add("catedra.kafka.appointment-phone-topic", () -> "test.phone." + SUFFIX);
        registry.add("spring.kafka.consumer.group-id", () -> "test-turns-" + SUFFIX);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ReservationProcessRepository repository;

    @Autowired
    private DataSource dataSource;

    @Value("${catedra.kafka.appointment-actions-topic}")
    private String actionsTopic;

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
        jdbc.update("delete from reservation_processes where user_id = ?", USER);
        jdbc.update("delete from processed_events where event_id like 'it-%'");
    }

    // ---------- helpers ----------

    private ReservationProcess waitingProcess(String tag, int hour) {
        Instant now = Instant.now();
        ReservationProcess process = repository.create(ReservationProcess.start(USER, "user-" + USER, 900_001L, "Ana",
                "Gomez", LocalDate.of(2027, 1, 4), LocalTime.of(hour, 0), LocalTime.of(hour, 30), "Juan", "Perez", now));
        process.holdCreated("it-p-" + tag, "it-h-" + tag, now.plusSeconds(600), now);
        process = repository.update(process);
        process.initialConfirmationAccepted(now.plusSeconds(600), now);
        return repository.update(process);
    }

    private void send(String processId, String json) throws Exception {
        kafkaTemplate.send(actionsTopic, processId, json).get();
    }

    private String requested(String eventId, String processId) {
        return """
                {"eventId": "%s", "eventType": "AdditionalInformationRequested", "reservationProcessId": "%s",
                 "holdId": "hold-x", "requiredField": "PHONE_NUMBER", "expiresAt": "2027-01-04T10:00:00Z",
                 "occurredAt": "2026-09-10T14:35:10Z", "schemaVersion": 1}
                """.formatted(eventId, processId);
    }

    private String confirmed(String eventId, String processId, long reservationId) {
        return """
                {"eventId": "%s", "eventType": "AppointmentConfirmed", "reservationProcessId": "%s", "holdId": "hold-x",
                 "requestEventId": "req-x", "reservationId": %d, "status": "CONFIRMED", "schemaVersion": 1}
                """.formatted(eventId, processId, reservationId);
    }

    private String simple(String eventId, String type, String processId, String reason) {
        return """
                {"eventId": "%s", "eventType": "%s", "reservationProcessId": "%s", "holdId": "hold-x",
                 "reason": "%s", "schemaVersion": 1}
                """.formatted(eventId, type, processId, reason);
    }

    private ReservationStatus statusOf(ReservationProcess process) {
        return repository.findById(process.getId()).orElseThrow().getStatus();
    }

    private long registeredEvents(String eventId) {
        return jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Long.class, eventId);
    }

    // ---------- tests ----------

    @Test
    @DisplayName("Flujo completo: pedido de telefono, duplicado, mensaje ilegible, confirmacion, vencimiento tardio y cancelacion")
    void shouldApplyTheFullFlowOfEvents() throws Exception {
        ReservationProcess first = waitingProcess("1", 9);
        ReservationProcess second = waitingProcess("2", 10);

        // Pedido de telefono, y su entrega duplicada
        send("it-p-1", requested("it-req-1", "it-p-1"));
        send("it-p-1", requested("it-req-1", "it-p-1"));
        // Un mensaje ilegible en el medio: se descarta y el consumidor sigue
        send("it-p-1", "esto no es json");
        // Confirmacion
        send("it-p-1", confirmed("it-conf-1", "it-p-1", 9101L));
        // Un vencimiento tardio: la reserva confirmada no se toca
        send("it-p-1", simple("it-exp-late", "AppointmentProcessExpired", "it-p-1", "HOLD_EXPIRED"));
        // Otro proceso: pedido de telefono y vencimiento
        send("it-p-2", requested("it-req-2", "it-p-2"));
        send("it-p-2", simple("it-exp-2", "AppointmentProcessExpired", "it-p-2", "HOLD_EXPIRED"));
        // Una confirmacion tardia sobre el proceso vencido: no lo reabre
        send("it-p-2", confirmed("it-conf-late", "it-p-2", 9102L));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertEquals(1L, registeredEvents("it-conf-late")));

        ReservationProcess storedFirst = repository.findById(first.getId()).orElseThrow();
        assertEquals(ReservationStatus.CONFIRMED, storedFirst.getStatus());
        assertEquals("it-req-1", storedFirst.getRequestEventId());
        assertEquals(9101L, storedFirst.getReservationId());
        assertEquals(1L, registeredEvents("it-req-1"), "el duplicado no se registra dos veces");
        assertEquals(1L, registeredEvents("it-exp-late"), "un evento que no aplica queda registrado");

        ReservationProcess storedSecond = repository.findById(second.getId()).orElseThrow();
        assertEquals(ReservationStatus.EXPIRED, storedSecond.getStatus());
        assertEquals(null, storedSecond.getReservationId());

        // Cancelacion de la reserva confirmada
        send("it-p-1", """
                {"eventId": "it-cancel-1", "eventType": "AppointmentCancelled", "reservationProcessId": "it-p-1",
                 "reservationId": 9101, "status": "CANCELLED", "reason": null, "schemaVersion": 1}
                """);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertEquals(ReservationStatus.CANCELLED, statusOf(first)));
    }

    @Test
    @DisplayName("Un evento de un proceso desconocido se descarta, no se registra y no bloquea los siguientes")
    void shouldDiscardEventsOfUnknownProcessesAndKeepConsuming() throws Exception {
        ReservationProcess known = waitingProcess("3", 11);

        send("it-p-desconocido", requested("it-req-unknown", "it-p-desconocido"));
        send("it-p-3", requested("it-req-3", "it-p-3"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertEquals(ReservationStatus.PHONE_REQUESTED, statusOf(known)));
        assertEquals(0L, registeredEvents("it-req-unknown"));
    }
}