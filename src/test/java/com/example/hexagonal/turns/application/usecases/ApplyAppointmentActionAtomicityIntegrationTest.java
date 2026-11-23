package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;
import com.example.hexagonal.turns.domain.model.PhoneNumber;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.ApplyAppointmentActionUseCase;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Requiere el compose activo (PostgreSQL). Prueba que el registro del evento y el cambio de estado van juntos. */
@SpringBootTest
class ApplyAppointmentActionAtomicityIntegrationTest {

    private static final long USER = 990_501L;

    @Autowired
    private ApplyAppointmentActionUseCase useCase;

    @Autowired
    private ReservationProcessRepository repository;

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
        jdbc.update("delete from reservation_processes where user_id = ?", USER);
        jdbc.update("delete from processed_events where event_id like 'it-%'");
    }

    private ReservationProcess submittedProcess(String tag, int hour) {
        Instant now = Instant.now();
        ReservationProcess p = repository.create(ReservationProcess.start(USER, "user-" + USER, 900_001L, "Ana", "Gomez",
                LocalDate.of(2027, 1, 4), LocalTime.of(hour, 0), LocalTime.of(hour, 30), "Juan", "Perez", now));
        p.holdCreated("it-atom-p" + tag, "it-atom-h" + tag, now.plusSeconds(600), now);
        p = repository.update(p);
        p.initialConfirmationAccepted(now.plusSeconds(600), now);
        p = repository.update(p);
        p.phoneRequested("it-atom-req" + tag, now.plusSeconds(600), now);
        p = repository.update(p);
        p.phoneSubmitted(PhoneNumber.of("2615551234"), now);
        return repository.update(p);
    }

    @Test
    @DisplayName("Si el cambio de estado falla, el registro del evento se deshace: se podra reprocesar")
    void shouldRollBackTheEventRegistrationWhenTheChangeFails() {
        // Una reserva ya confirmada con el id 9501
        ReservationProcess confirmed = submittedProcess("a", 9);
        confirmed.confirmed(9501L, Instant.now());
        repository.update(confirmed);
        // Otra, que recibe un evento de confirmacion con el MISMO id de reserva: la restriccion unica de la base lo rechaza
        ReservationProcess other = submittedProcess("b", 10);
        AppointmentActionEvent event = new AppointmentActionEvent("it-atomic-1",
                AppointmentActionEvent.Type.CONFIRMED, "it-atom-pb", "it-atom-hb", "it-atom-reqb", 9501L, null, null);

        assertThrows(DataIntegrityViolationException.class, () -> useCase.apply(event));

        long registered = jdbc.queryForObject(
                "select count(*) from processed_events where event_id = 'it-atomic-1'", Long.class);
        assertEquals(0L, registered, "el evento no debe quedar registrado si su cambio no se aplico");
        assertEquals(ReservationStatus.PHONE_SUBMITTED, repository.findById(other.getId()).orElseThrow().getStatus());
    }
}
