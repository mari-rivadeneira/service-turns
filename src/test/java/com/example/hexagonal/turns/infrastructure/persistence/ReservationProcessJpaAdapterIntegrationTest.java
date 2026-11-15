package com.example.hexagonal.turns.infrastructure.persistence;

import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.model.PhoneNumber;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requiere el compose activo (PostgreSQL). No es transaccional: cada operacion del adaptador confirma su propia
 * transaccion, que es lo que permite probar el conflicto de versiones. Los datos de prueba se borran al terminar.
 */
@SpringBootTest
class ReservationProcessJpaAdapterIntegrationTest {

    private static final long USER = 990_101L;
    private static final long OTHER_USER = 990_102L;
    // PostgreSQL guarda microsegundos: se trunca para poder comparar lo guardado con lo original
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Instant EXPIRES = NOW.plusSeconds(600);

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
        jdbc.update("delete from reservation_processes where user_id in (?, ?)", USER, OTHER_USER);
    }

    // ---------- helpers ----------

    private ReservationProcess process(long userId, int hour, Instant createdAt) {
        return ReservationProcess.start(userId, "user-" + userId, 900_001L, "Ana", "Gomez",
                LocalDate.of(2026, 12, 1), LocalTime.of(hour, 0), LocalTime.of(hour, 30), "Juan", "Perez", createdAt);
    }

    private ReservationProcess advanceToPhoneSubmitted(ReservationProcess created, String tag, Instant expiresAt) {
        ReservationProcess p = created;
        p.holdCreated("it-p-" + tag, "it-h-" + tag, expiresAt, NOW);
        p = repository.update(p);
        p.initialConfirmationAccepted(expiresAt, NOW);
        p = repository.update(p);
        p.phoneRequested("it-req-" + tag, expiresAt, NOW);
        p = repository.update(p);
        p.phoneSubmitted(PhoneNumber.of("2615551234"), NOW);
        return repository.update(p);
    }

    private ReservationProcess advanceToConfirmed(ReservationProcess created, String tag, long reservationId,
                                                  Instant expiresAt) {
        ReservationProcess p = advanceToPhoneSubmitted(created, tag, expiresAt);
        p.confirmed(reservationId, NOW);
        return repository.update(p);
    }

    private Set<UUID> ids(List<ReservationProcess> processes) {
        return processes.stream().map(ReservationProcess::getId).collect(Collectors.toSet());
    }

    // ---------- ida y vuelta ----------

    @Test
    @DisplayName("Guarda un proceso y lo recupera con todos sus campos; cada cambio sube la version")
    void shouldCreateUpdateAndReloadAllFields() {
        ReservationProcess created = repository.create(process(USER, 9, NOW));
        assertEquals(0L, created.getVersion());
        assertEquals(ReservationStatus.STARTED, created.getStatus());

        ReservationProcess loaded = repository.findById(created.getId()).orElseThrow();
        assertEquals(USER, loaded.getUserId());
        assertEquals("user-" + USER, loaded.getExternalPatientId());
        assertEquals(900_001L, loaded.getProfessionalId());
        assertEquals("Ana", loaded.getProfessionalFirstName());
        assertEquals("Gomez", loaded.getProfessionalLastName());
        assertEquals(LocalDate.of(2026, 12, 1), loaded.getAppointmentDate());
        assertEquals(LocalTime.of(9, 0), loaded.getStartTime());
        assertEquals(LocalTime.of(9, 30), loaded.getEndTime());
        assertEquals("Juan", loaded.getPatientFirstName());
        assertEquals("Perez", loaded.getPatientLastName());
        assertEquals(NOW, loaded.getCreatedAt());
        assertNull(loaded.getCatedraProcessId());
        assertNull(loaded.getHoldId());
        assertNull(loaded.getExpiresAt());

        loaded.holdCreated("it-p-1", "it-h-1", EXPIRES, NOW.plusSeconds(1));
        ReservationProcess updated = repository.update(loaded);
        assertEquals(1L, updated.getVersion());

        ReservationProcess reloaded = repository.findById(created.getId()).orElseThrow();
        assertEquals(ReservationStatus.HELD, reloaded.getStatus());
        assertEquals("it-p-1", reloaded.getCatedraProcessId());
        assertEquals("it-h-1", reloaded.getHoldId());
        assertEquals(EXPIRES, reloaded.getExpiresAt());
        assertEquals(NOW.plusSeconds(1), reloaded.getUpdatedAt());
        assertEquals(1L, reloaded.getVersion());

        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("Ciclo de vida completo hasta CONFIRMED: se conservan el telefono, el requestEventId y la reserva")
    void shouldPersistTheFullLifecycle() {
        ReservationProcess created = repository.create(process(USER, 9, NOW));

        ReservationProcess confirmed = advanceToConfirmed(created, "life", 9001L, EXPIRES);

        assertEquals(5L, confirmed.getVersion());
        ReservationProcess stored = repository.findById(created.getId()).orElseThrow();
        assertEquals(ReservationStatus.CONFIRMED, stored.getStatus());
        assertEquals(9001L, stored.getReservationId());
        assertEquals(NOW, stored.getConfirmedAt());
        assertEquals("2615551234", stored.getPatientPhone());
        assertEquals("it-req-life", stored.getRequestEventId());
        assertEquals(5L, stored.getVersion());
    }

    // ---------- consultas ----------

    @Test
    @DisplayName("Un proceso solo se encuentra con su dueno: el de otro usuario es indistinguible de uno inexistente")
    void shouldFindByIdOnlyForTheOwner() {
        ReservationProcess created = repository.create(process(USER, 9, NOW));

        assertTrue(repository.findByIdAndUserId(created.getId(), USER).isPresent());
        assertTrue(repository.findByIdAndUserId(created.getId(), OTHER_USER).isEmpty());
        assertTrue(repository.findByIdAndUserId(UUID.randomUUID(), USER).isEmpty());
    }

    @Test
    @DisplayName("Busca por el id del proceso de la catedra")
    void shouldFindByCatedraProcessId() {
        ReservationProcess created = repository.create(process(USER, 9, NOW));
        created.holdCreated("it-p-find", "it-h-find", EXPIRES, NOW);
        repository.update(created);

        assertEquals(created.getId(), repository.findByCatedraProcessId("it-p-find").orElseThrow().getId());
        assertTrue(repository.findByCatedraProcessId("it-p-no-existe").isEmpty());
    }

    @Test
    @DisplayName("Los procesos de un usuario salen del mas nuevo al mas viejo y sin los de otros usuarios")
    void shouldListOwnProcessesNewestFirst() {
        ReservationProcess oldest = repository.create(process(USER, 9, NOW));
        ReservationProcess middle = repository.create(process(USER, 10, NOW.plusSeconds(1)));
        ReservationProcess newest = repository.create(process(USER, 11, NOW.plusSeconds(2)));
        repository.create(process(OTHER_USER, 12, NOW.plusSeconds(3)));

        List<ReservationProcess> mine = repository.findByUserId(USER);

        assertEquals(List.of(newest.getId(), middle.getId(), oldest.getId()),
                mine.stream().map(ReservationProcess::getId).toList());
        assertTrue(mine.stream().allMatch(p -> p.isOwnedBy(USER)));
    }

    @Test
    @DisplayName("Procesos vencidos: solo los que estan en curso y ya pasaron su vigencia")
    void shouldFindOnlyExpirableProcesses() {
        Instant past = NOW.minusSeconds(120);

        ReservationProcess heldPast = repository.create(process(USER, 9, NOW));
        heldPast.holdCreated("it-p-a", "it-h-a", past, NOW);
        heldPast = repository.update(heldPast);

        ReservationProcess submittedPast = advanceToPhoneSubmitted(repository.create(process(USER, 10, NOW)), "b", past);

        ReservationProcess heldFuture = repository.create(process(USER, 11, NOW));
        heldFuture.holdCreated("it-p-c", "it-h-c", EXPIRES, NOW);
        heldFuture = repository.update(heldFuture);

        ReservationProcess confirmedPast = advanceToConfirmed(repository.create(process(USER, 12, NOW)), "d", 9004L, past);

        ReservationProcess started = repository.create(process(USER, 13, NOW));

        ReservationProcess failedPast = repository.create(process(USER, 14, NOW));
        failedPast.holdCreated("it-p-f", "it-h-f", past, NOW);
        failedPast = repository.update(failedPast);
        failedPast.failed("fallo", NOW);
        failedPast = repository.update(failedPast);

        Set<UUID> expirable = ids(repository.findExpirable(NOW));

        assertTrue(expirable.contains(heldPast.getId()));
        assertTrue(expirable.contains(submittedPast.getId()));
        assertFalse(expirable.contains(heldFuture.getId()));
        assertFalse(expirable.contains(confirmedPast.getId()));
        assertFalse(expirable.contains(started.getId()));
        assertFalse(expirable.contains(failedPast.getId()));
    }

    @Test
    @DisplayName("Procesos iniciados sin respuesta: solo los STARTED anteriores al umbral")
    void shouldFindOnlyOldStartedProcesses() {
        ReservationProcess oldStarted = repository.create(process(USER, 9, NOW.minus(2, ChronoUnit.HOURS)));
        ReservationProcess recentStarted = repository.create(process(USER, 10, NOW));
        ReservationProcess oldHeld = repository.create(process(USER, 11, NOW.minus(2, ChronoUnit.HOURS)));
        oldHeld.holdCreated("it-p-old", "it-h-old", EXPIRES, NOW);
        oldHeld = repository.update(oldHeld);

        Set<UUID> found = ids(repository.findStartedBefore(NOW.minus(1, ChronoUnit.HOURS)));

        assertTrue(found.contains(oldStarted.getId()));
        assertFalse(found.contains(recentStarted.getId()));
        assertFalse(found.contains(oldHeld.getId()));
    }

    // ---------- concurrencia y restricciones ----------

    @Test
    @DisplayName("Conflicto de versiones: la segunda modificacion sobre una lectura vieja se rechaza y no pisa la primera")
    void shouldRejectUpdatesBasedOnAStaleRead() {
        ReservationProcess created = repository.create(process(USER, 9, NOW));
        ReservationProcess copyA = repository.findById(created.getId()).orElseThrow();
        ReservationProcess copyB = repository.findById(created.getId()).orElseThrow();

        copyA.holdCreated("it-p-a", "it-h-a", EXPIRES, NOW);
        repository.update(copyA);

        copyB.failed("La catedra no respondio", NOW);
        assertThrows(ConcurrentUpdateException.class, () -> repository.update(copyB));

        ReservationProcess stored = repository.findById(created.getId()).orElseThrow();
        assertEquals(ReservationStatus.HELD, stored.getStatus());
        assertEquals("it-p-a", stored.getCatedraProcessId());
        assertEquals(1L, stored.getVersion());
    }

    @Test
    @DisplayName("Dos reservas confirmadas para el mismo turno: la base rechaza la segunda y su proceso queda como estaba")
    void shouldRejectASecondConfirmedReservationForTheSameSlot() {
        advanceToConfirmed(repository.create(process(USER, 9, NOW)), "s1", 9101L, EXPIRES);
        ReservationProcess second = repository.create(process(OTHER_USER, 9, NOW));

        assertThrows(DataIntegrityViolationException.class,
                () -> advanceToConfirmed(second, "s2", 9102L, EXPIRES));

        ReservationProcess stored = repository.findByUserId(OTHER_USER).get(0);
        assertEquals(ReservationStatus.PHONE_SUBMITTED, stored.getStatus());
        assertNull(stored.getReservationId());
        assertNotNull(stored.getPatientPhone());
    }
}
