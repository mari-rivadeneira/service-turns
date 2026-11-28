package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.model.CatedraAppointment;
import com.example.hexagonal.turns.domain.model.CatedraAppointmentPage;
import com.example.hexagonal.turns.domain.model.PhoneNumber;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.RecoverReservationsUseCase;
import com.example.hexagonal.turns.domain.port.in.RecoverReservationsUseCase.RecoveryReport;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Base real (compose activo); la catedra se simula. La recuperacion recorre toda la tabla: si la base tiene reservas vencidas de
 * pruebas manuales, tambien las trata (por eso las cantidades del informe se comparan con >=). Se borran solo los procesos de prueba.
 */
@SpringBootTest
class ReservationRecoveryIntegrationTest {

    private static final long USER = 990_801L;
    private static final LocalDate DATE = LocalDate.of(2027, 1, 4);

    @Autowired
    private RecoverReservationsUseCase recoverUseCase;

    @Autowired
    private ReservationProcessRepository repository;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private CatedraAppointmentClient catedraClient;

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
    }

    private ReservationProcess started(int hour, Instant createdAt) {
        return repository.create(ReservationProcess.start(USER, "user-" + USER, 900_001L, "Ana", "Gomez", DATE,
                LocalTime.of(hour, 0), LocalTime.of(hour, 30), "Juan", "Perez", createdAt));
    }

    private ReservationProcess held(int hour, String tag, Instant expiresAt) {
        Instant now = Instant.now();
        ReservationProcess p = started(hour, now.minus(30, ChronoUnit.MINUTES));
        p.holdCreated("it-rec-" + tag, "it-rech-" + tag, expiresAt, now);
        return repository.update(p);
    }

    private ReservationProcess submitted(int hour, String tag, Instant expiresAt) {
        Instant now = Instant.now();
        ReservationProcess p = held(hour, tag, expiresAt);
        p.initialConfirmationAccepted(expiresAt, now);
        p = repository.update(p);
        p.phoneRequested("it-recreq-" + tag, expiresAt, now);
        p = repository.update(p);
        p.phoneSubmitted(PhoneNumber.of("2615551234"), now);
        return repository.update(p);
    }

    private ReservationStatus statusOf(ReservationProcess process) {
        return repository.findById(process.getId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("Una corrida cierra los abandonados, vence lo que la catedra no conoce y recupera una confirmacion perdida")
    void shouldRecoverPendingProcesses() {
        Instant past = Instant.now().minus(10, ChronoUnit.MINUTES);
        ReservationProcess abandoned = started(9, Instant.now().minus(20, ChronoUnit.MINUTES));
        ReservationProcess expiredHold = held(10, "hold", past);
        ReservationProcess lostConfirmation = submitted(11, "conf", past);
        ReservationProcess stillValid = held(12, "valid", Instant.now().plus(10, ChronoUnit.MINUTES));
        CatedraAppointment confirmed = new CatedraAppointment(9801L, "it-rec-conf", "user-" + USER, DATE,
                LocalTime.of(11, 0), LocalTime.of(11, 30), 900_001L, "Ana", "Gomez", "Juan", "Perez", "+542615551234",
                CatedraAppointment.Status.CONFIRMED, Instant.now(), null, null, null);
        when(catedraClient.listAppointments(any())).thenReturn(new CatedraAppointmentPage(List.of(confirmed), 1));

        RecoveryReport report = recoverUseCase.recover();

        assertEquals(ReservationStatus.FAILED, statusOf(abandoned));
        assertEquals("START_ABANDONED",
                jdbc.queryForObject("select status_reason from reservation_processes where id = ?", String.class,
                        abandoned.getId()));
        assertEquals(ReservationStatus.EXPIRED, statusOf(expiredHold));
        assertEquals(ReservationStatus.CONFIRMED, statusOf(lostConfirmation));
        assertEquals(9801L, repository.findById(lostConfirmation.getId()).orElseThrow().getReservationId());
        assertEquals(ReservationStatus.HELD, statusOf(stillValid));
        assertTrue(report.abandonedStarts() >= 1);
        assertTrue(report.reconciled() >= 2);
        assertTrue(report.changed() >= 2);
    }

    @Test
    @DisplayName("Una segunda corrida no vuelve a cambiar lo ya recuperado")
    void shouldNotChangeAnythingOnASecondRun() {
        ReservationProcess expiredHold = held(10, "again", Instant.now().minus(10, ChronoUnit.MINUTES));
        when(catedraClient.listAppointments(any())).thenReturn(new CatedraAppointmentPage(List.of(), 0));
        recoverUseCase.recover();
        long versionAfterFirst = repository.findById(expiredHold.getId()).orElseThrow().getVersion();

        recoverUseCase.recover();

        assertEquals(ReservationStatus.EXPIRED, statusOf(expiredHold));
        assertEquals(versionAfterFirst, repository.findById(expiredHold.getId()).orElseThrow().getVersion());
    }
}
