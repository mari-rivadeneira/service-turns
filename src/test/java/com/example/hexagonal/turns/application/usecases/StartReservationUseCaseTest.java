package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatalogUnavailableException;
import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.application.exception.InvalidRequestException;
import com.example.hexagonal.turns.application.exception.ProfessionalDisabledException;
import com.example.hexagonal.turns.application.exception.ProfessionalNotFoundException;
import com.example.hexagonal.turns.application.exception.ReservationInProgressException;
import com.example.hexagonal.turns.application.exception.SlotUnavailableException;
import com.example.hexagonal.turns.domain.model.CatalogProfessional;
import com.example.hexagonal.turns.domain.model.CatalogSchedule;
import com.example.hexagonal.turns.domain.model.CatedraHold;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase.StartReservationCommand;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase.StartReservationResult;
import com.example.hexagonal.turns.domain.port.out.CatalogClient;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartReservationUseCaseTest {

    private static final String TOKEN = "jwt-del-usuario";
    private static final long USER = 42L;
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final LocalDate MONDAY = LocalDate.of(2026, 12, 7);
    // Viernes 20/11/2026, 12:00 hora de atencion
    private static final Clock FRIDAY_NOON = Clock.fixed(Instant.parse("2026-11-20T15:00:00Z"), ZONE);
    // Lunes 7/12/2026, 10:15 hora de atencion
    private static final Clock MONDAY_1015 = Clock.fixed(Instant.parse("2026-12-07T13:15:00Z"), ZONE);
    private static final Instant EXPIRES = Instant.parse("2026-11-20T15:10:00Z");

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private CatedraAppointmentClient catedraClient;

    private final InMemoryReservationProcessRepository repository = new InMemoryReservationProcessRepository();

    private StartReservationUseCaseImpl useCase(Clock clock) {
        return new StartReservationUseCaseImpl(catalogClient, catedraClient, repository, clock);
    }

    private StartReservationCommand command(LocalTime startTime) {
        return new StartReservationCommand(USER, TOKEN, 15L, MONDAY, startTime, "Juan", "Perez");
    }

    private void stubCatalog() {
        when(catalogClient.findProfessional(15L, TOKEN))
                .thenReturn(Optional.of(new CatalogProfessional(15L, 1L, "Ana", "Gomez", true)));
        when(catalogClient.findSchedules(15L, TOKEN)).thenReturn(Optional.of(List.of(
                new CatalogSchedule(21L, 15L, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(11, 0), 30, true))));
    }

    private void stubHold() {
        when(catedraClient.createHold(anyLong(), any(), any(), anyString())).thenReturn(hold());
    }

    private CatedraHold hold() {
        return new CatedraHold("hold-6c0c2f41", "process-a92b47d0", "HELD", EXPIRES);
    }

    private ReservationProcess existingProcess(ReservationStatus status) {
        ReservationProcess process = repository.create(ReservationProcess.start(USER, "user-42", 15L, "Ana", "Gomez",
                MONDAY, LocalTime.of(9, 30), LocalTime.of(10, 0), "Juan", "Perez", Instant.parse("2026-11-20T14:00:00Z")));
        if (status != ReservationStatus.STARTED) {
            repository.modify(process.getId(), p -> {
                p.setCatedraProcessId("process-old");
                p.setHoldId("hold-old");
                p.setStatus(status);
            });
        }
        return repository.get(process.getId());
    }

    private ReservationProcess onlyProcess() {
        assertEquals(1, repository.count());
        return repository.all().get(0);
    }

    // ---------- camino feliz ----------

    @Test
    @DisplayName("Guarda la intencion ANTES de llamar a la catedra y termina con el hold guardado a nombre del usuario")
    void shouldPersistIntentionBeforeCallingCatedraAndEndHeld() {
        stubCatalog();
        when(catedraClient.createHold(anyLong(), any(), any(), anyString())).thenAnswer(invocation -> {
            // En este instante ya tiene que existir el proceso, todavia sin datos de la catedra
            assertEquals(1, repository.count());
            ReservationProcess beforeCall = repository.all().get(0);
            assertEquals(ReservationStatus.STARTED, beforeCall.getStatus());
            assertNull(beforeCall.getHoldId());
            return hold();
        });

        StartReservationResult result = useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30)));

        assertTrue(result.created());
        ReservationProcess process = onlyProcess();
        assertEquals(ReservationStatus.HELD, process.getStatus());
        assertEquals("hold-6c0c2f41", process.getHoldId());
        assertEquals("process-a92b47d0", process.getCatedraProcessId());
        assertEquals(EXPIRES, process.getExpiresAt());
        assertEquals(USER, process.getUserId());
        assertEquals("user-42", process.getExternalPatientId());
        assertEquals(15L, process.getProfessionalId());
        assertEquals("Ana", process.getProfessionalFirstName());
        assertEquals("Gomez", process.getProfessionalLastName());
        assertEquals(MONDAY, process.getAppointmentDate());
        assertEquals(LocalTime.of(9, 30), process.getStartTime());
        assertEquals(LocalTime.of(10, 0), process.getEndTime());
        assertEquals("Juan", process.getPatientFirstName());
        assertEquals(result.process().getId(), process.getId());
        verify(catedraClient).createHold(15L, MONDAY, LocalTime.of(9, 30), "user-42");
    }

    @Test
    @DisplayName("Los nombres del paciente se guardan sin espacios sobrantes")
    void shouldTrimPatientNames() {
        stubCatalog();
        stubHold();

        useCase(FRIDAY_NOON).start(new StartReservationCommand(USER, TOKEN, 15L, MONDAY, LocalTime.of(9, 30),
                "  Juan ", " Perez  "));

        assertEquals("Juan", onlyProcess().getPatientFirstName());
        assertEquals("Perez", onlyProcess().getPatientLastName());
    }

    // ---------- idempotencia ----------

    @Test
    @DisplayName("Repetir el pedido para el mismo turno devuelve el proceso existente, sin llamar a nadie")
    void shouldReturnExistingProcessWhenTheRequestIsRepeated() {
        ReservationProcess existing = existingProcess(ReservationStatus.HELD);

        StartReservationResult result = useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30)));

        assertFalse(result.created());
        assertEquals(existing.getId(), result.process().getId());
        assertEquals(1, repository.count());
        verifyNoInteractions(catalogClient, catedraClient);
    }

    @Test
    @DisplayName("Si el pedido anterior todavia se esta procesando (STARTED), informa que hay una reserva en curso")
    void shouldReportReservationInProgress() {
        existingProcess(ReservationStatus.STARTED);

        assertThrows(ReservationInProgressException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        verifyNoInteractions(catalogClient, catedraClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "EXPIRED", "CANCELLED", "INVALID"})
    @DisplayName("Un proceso anterior que ya termino no bloquea: se inicia uno nuevo")
    void shouldIgnoreFinishedProcessesOfTheSameSlot(String status) {
        existingProcess(ReservationStatus.valueOf(status));
        stubCatalog();
        stubHold();

        StartReservationResult result = useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30)));

        assertTrue(result.created());
        assertEquals(2, repository.count());
    }

    // ---------- validacion ----------

    @Test
    @DisplayName("Datos invalidos: informa cada campo y no guarda ni llama a nadie")
    void shouldRejectInvalidInput() {
        StartReservationUseCaseImpl useCase = useCase(FRIDAY_NOON);

        InvalidRequestException missing = assertThrows(InvalidRequestException.class, () -> useCase.start(
                new StartReservationCommand(USER, TOKEN, null, null, null, " ", null)));
        InvalidRequestException pastDate = assertThrows(InvalidRequestException.class, () -> useCase.start(
                new StartReservationCommand(USER, TOKEN, 15L, LocalDate.of(2026, 11, 19), LocalTime.of(9, 0),
                        "Juan", "Perez")));
        InvalidRequestException badNames = assertThrows(InvalidRequestException.class, () -> useCase.start(
                new StartReservationCommand(USER, TOKEN, 0L, MONDAY, LocalTime.of(9, 0), "J", "x".repeat(101))));

        assertTrue(missing.getFieldErrors().keySet().containsAll(
                List.of("professionalId", "date", "startTime", "patientFirstName", "patientLastName")));
        assertTrue(pastDate.getFieldErrors().containsKey("date"));
        assertTrue(badNames.getFieldErrors().keySet().containsAll(
                List.of("professionalId", "patientFirstName", "patientLastName")));
        assertEquals(0, repository.count());
        verifyNoInteractions(catalogClient, catedraClient);
    }

    @Test
    @DisplayName("Un horario que no es un turno de la agenda se rechaza y no se guarda nada")
    void shouldRejectStartTimeThatIsNotASlot() {
        stubCatalog();

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 10))));

        assertTrue(ex.getFieldErrors().containsKey("startTime"));
        assertEquals(0, repository.count());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Un turno de hoy que ya empezo se rechaza")
    void shouldRejectASlotThatAlreadyStartedToday() {
        stubCatalog();

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> useCase(MONDAY_1015).start(command(LocalTime.of(10, 0))));

        assertTrue(ex.getFieldErrors().containsKey("startTime"));
        assertEquals(0, repository.count());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Un profesional inexistente o deshabilitado se rechaza antes de guardar nada")
    void shouldRejectUnknownOrDisabledProfessional() {
        when(catalogClient.findProfessional(15L, TOKEN)).thenReturn(Optional.empty());
        assertThrows(ProfessionalNotFoundException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        when(catalogClient.findProfessional(15L, TOKEN))
                .thenReturn(Optional.of(new CatalogProfessional(15L, 1L, "Ana", "Gomez", false)));
        assertThrows(ProfessionalDisabledException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        assertEquals(0, repository.count());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Si service-catalog no esta disponible, no se inicia nada: no se puede validar informacion vigente")
    void shouldNotStartWhenCatalogIsUnavailable() {
        when(catalogClient.findProfessional(15L, TOKEN))
                .thenThrow(new CatalogUnavailableException("catalogo caido", null));

        assertThrows(CatalogUnavailableException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        assertEquals(0, repository.count());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Sin JWT del usuario no se hace nada")
    void shouldRequireAccessToken() {
        assertThrows(IllegalArgumentException.class, () -> useCase(FRIDAY_NOON).start(
                new StartReservationCommand(USER, " ", 15L, MONDAY, LocalTime.of(9, 30), "Juan", "Perez")));

        verifyNoInteractions(catalogClient, catedraClient);
    }

    // ---------- rechazos firmes de la catedra ----------

    @ParameterizedTest
    @ValueSource(strings = {"SLOT_ALREADY_HELD", "SLOT_ALREADY_RESERVED"})
    @DisplayName("Turno tomado: SlotUnavailableException y el proceso queda FAILED con el code de la catedra")
    void shouldTranslateSlotTaken(String code) {
        stubCatalog();
        when(catedraClient.createHold(anyLong(), any(), any(), anyString()))
                .thenThrow(new CatedraRejectedException(409, code, "detalle"));

        assertThrows(SlotUnavailableException.class, () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        ReservationProcess process = onlyProcess();
        assertEquals(ReservationStatus.FAILED, process.getStatus());
        assertEquals(code, process.getStatusReason());
        assertNull(process.getHoldId());
    }

    @Test
    @DisplayName("PROFESSIONAL_DISABLED e INVALID_SLOT de la catedra se traducen y dejan el proceso FAILED")
    void shouldTranslateDisabledProfessionalAndInvalidSlot() {
        stubCatalog();
        when(catedraClient.createHold(anyLong(), any(), any(), anyString()))
                .thenThrow(new CatedraRejectedException(400, "PROFESSIONAL_DISABLED", "x"))
                .thenThrow(new CatedraRejectedException(400, "INVALID_SLOT", "x"));

        assertThrows(ProfessionalDisabledException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));
        InvalidRequestException invalidSlot = assertThrows(InvalidRequestException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        assertTrue(invalidSlot.getFieldErrors().containsKey("startTime"));
        assertTrue(repository.all().stream().allMatch(p -> p.getStatus() == ReservationStatus.FAILED));
    }

    @Test
    @DisplayName("Un rechazo inesperado (por ejemplo, un 401 sin code) se propaga y deja el proceso FAILED")
    void shouldRethrowUnknownRejections() {
        stubCatalog();
        when(catedraClient.createHold(anyLong(), any(), any(), anyString()))
                .thenThrow(new CatedraRejectedException(401, null, null));

        assertThrows(CatedraRejectedException.class, () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        ReservationProcess process = onlyProcess();
        assertEquals(ReservationStatus.FAILED, process.getStatus());
        assertEquals("CATEDRA_REJECTED_401", process.getStatusReason());
    }

    // ---------- resultado incierto ----------

    @Test
    @DisplayName("Resultado incierto (el pedido pudo llegar): el proceso queda FAILED con su motivo y no se repite el POST")
    void shouldMarkFailedWithUnknownOutcomeAndNotRetry() {
        stubCatalog();
        when(catedraClient.createHold(anyLong(), any(), any(), anyString()))
                .thenThrow(new CatedraUnavailableException("timeout de lectura", true, null));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        assertTrue(ex.isOutcomeUnknown());
        ReservationProcess process = onlyProcess();
        assertEquals(ReservationStatus.FAILED, process.getStatus());
        assertEquals("HOLD_OUTCOME_UNKNOWN", process.getStatusReason());
        verify(catedraClient, times(1)).createHold(anyLong(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Si el pedido seguro no salio (conexion rechazada), el motivo es distinto")
    void shouldMarkFailedWhenTheRequestWasNotDelivered() {
        stubCatalog();
        when(catedraClient.createHold(anyLong(), any(), any(), anyString()))
                .thenThrow(new CatedraUnavailableException("conexion rechazada", false, null));

        assertThrows(CatedraUnavailableException.class, () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        assertEquals("CATEDRA_UNAVAILABLE", onlyProcess().getStatusReason());
    }

    @Test
    @DisplayName("Un hold incompleto se trata como resultado incierto")
    void shouldTreatAnIncompleteHoldAsUncertain() {
        stubCatalog();
        when(catedraClient.createHold(anyLong(), any(), any(), anyString()))
                .thenReturn(new CatedraHold(null, "process-1", "HELD", EXPIRES));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        assertTrue(ex.isOutcomeUnknown());
        assertEquals("INVALID_HOLD_RESPONSE", onlyProcess().getStatusReason());
    }

    @Test
    @DisplayName("Si tampoco se puede guardar el fallo, se informa el error original y el proceso queda STARTED para la recuperacion")
    void shouldKeepTheOriginalErrorWhenMarkingTheFailureAlsoFails() {
        stubCatalog();
        when(catedraClient.createHold(anyLong(), any(), any(), anyString())).thenAnswer(invocation -> {
            repository.failOnUpdate(true);
            throw new CatedraUnavailableException("timeout de lectura", true, null);
        });

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class,
                () -> useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30))));

        assertNotNull(ex);
        assertEquals(ReservationStatus.STARTED, onlyProcess().getStatus());
    }

    @Test
    @DisplayName("El proceso devuelto es una lectura del guardado, con la version actualizada")
    void shouldReturnThePersistedProcess() {
        stubCatalog();
        stubHold();

        StartReservationResult result = useCase(FRIDAY_NOON).start(command(LocalTime.of(9, 30)));

        assertEquals(1L, result.process().getVersion());
        assertEquals(repository.get(result.process().getId()).getVersion(), result.process().getVersion());
        assertSame(ReservationStatus.HELD, result.process().getStatus());
        verify(catedraClient, never()).confirmHold(anyString(), anyString(), anyString(), anyString());
    }
}