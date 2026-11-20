package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatalogRejectedException;
import com.example.hexagonal.turns.application.exception.CatalogUnavailableException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.application.exception.InvalidRequestException;
import com.example.hexagonal.turns.application.exception.ProfessionalDisabledException;
import com.example.hexagonal.turns.application.exception.ProfessionalNotFoundException;
import com.example.hexagonal.turns.domain.model.Availability;
import com.example.hexagonal.turns.domain.model.AvailableSlot;
import com.example.hexagonal.turns.domain.model.CatalogProfessional;
import com.example.hexagonal.turns.domain.model.CatalogSchedule;
import com.example.hexagonal.turns.domain.model.SlotOccupancy;
import com.example.hexagonal.turns.domain.port.in.GetAvailabilityUseCase.AvailabilityQuery;
import com.example.hexagonal.turns.domain.port.out.CatalogClient;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAvailabilityUseCaseTest {

    private static final String TOKEN = "jwt-del-usuario";
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    // Lunes 7/12/2026
    private static final LocalDate MONDAY = LocalDate.of(2026, 12, 7);
    // Viernes 20/11/2026, 12:00 hora de atencion
    private static final Clock FRIDAY_NOON = Clock.fixed(Instant.parse("2026-11-20T15:00:00Z"), ZONE);
    // Lunes 7/12/2026, 10:15 hora de atencion
    private static final Clock MONDAY_1015 = Clock.fixed(Instant.parse("2026-12-07T13:15:00Z"), ZONE);

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private CatedraAppointmentClient catedraClient;

    private GetAvailabilityUseCaseImpl useCase(Clock clock, int maxAttempts) {
        return new GetAvailabilityUseCaseImpl(catalogClient, catedraClient, clock, maxAttempts, 0L);
    }

    private AvailabilityQuery query(LocalDate date) {
        return new AvailabilityQuery(15L, date, TOKEN);
    }

    private CatalogProfessional professional(boolean enabled) {
        return new CatalogProfessional(15L, 1L, "Ana", "Gomez", enabled);
    }

    private CatalogSchedule schedule(DayOfWeek day, String start, String end, int minutes) {
        return new CatalogSchedule(21L, 15L, day, LocalTime.parse(start), LocalTime.parse(end), minutes, true);
    }

    private SlotOccupancy occupancy(String start, SlotOccupancy.Status status) {
        return new SlotOccupancy(MONDAY, LocalTime.parse(start), status, null);
    }

    private List<LocalTime> starts(Availability availability) {
        return availability.slots().stream().map(AvailableSlot::startTime).toList();
    }

    private void stubCatalog(CatalogSchedule... schedules) {
        when(catalogClient.findProfessional(15L, TOKEN)).thenReturn(Optional.of(professional(true)));
        when(catalogClient.findSchedules(15L, TOKEN)).thenReturn(Optional.of(List.of(schedules)));
    }

    // ---------- calculo ----------

    @Test
    @DisplayName("Combina las agendas del catalogo con las ocupaciones de la catedra y reenvia el JWT del usuario")
    void shouldCombineSchedulesAndOccupancies() {
        stubCatalog(schedule(DayOfWeek.MONDAY, "09:00", "11:00", 30));
        when(catedraClient.getOccupancies(15L, MONDAY, MONDAY)).thenReturn(List.of(
                occupancy("09:00", SlotOccupancy.Status.CONFIRMED),
                occupancy("10:00", SlotOccupancy.Status.HELD)));

        Availability availability = useCase(FRIDAY_NOON, 1).getAvailability(query(MONDAY));

        assertEquals(15L, availability.professionalId());
        assertEquals(MONDAY, availability.date());
        assertEquals(List.of(LocalTime.of(9, 30), LocalTime.of(10, 30)), starts(availability));
        verify(catalogClient).findProfessional(15L, TOKEN);
        verify(catalogClient).findSchedules(15L, TOKEN);
    }

    @Test
    @DisplayName("Un dia sin agenda devuelve una lista vacia sin consultar a la catedra")
    void shouldReturnEmptyWithoutCallingCatedraWhenThereIsNoScheduleThatDay() {
        stubCatalog(schedule(DayOfWeek.TUESDAY, "09:00", "11:00", 30));

        Availability availability = useCase(FRIDAY_NOON, 1).getAvailability(query(MONDAY));

        assertTrue(availability.slots().isEmpty());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Si la fecha es hoy, los turnos que ya empezaron no se ofrecen")
    void shouldNotOfferSlotsThatAlreadyStartedToday() {
        stubCatalog(schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30));
        when(catedraClient.getOccupancies(15L, MONDAY, MONDAY))
                .thenReturn(List.of(occupancy("11:00", SlotOccupancy.Status.HELD)));

        Availability availability = useCase(MONDAY_1015, 1).getAvailability(query(MONDAY));

        assertEquals(List.of(LocalTime.of(10, 30), LocalTime.of(11, 30)), starts(availability));
    }

    @Test
    @DisplayName("Si ya empezaron todos los turnos de hoy, no consulta a la catedra")
    void shouldReturnEmptyWhenAllSlotsOfTodayAlreadyStarted() {
        stubCatalog(schedule(DayOfWeek.MONDAY, "09:00", "10:00", 30));

        Availability availability = useCase(MONDAY_1015, 1).getAvailability(query(MONDAY));

        assertTrue(availability.slots().isEmpty());
        verifyNoInteractions(catedraClient);
    }

    // ---------- profesional ----------

    @Test
    @DisplayName("Un profesional inexistente lanza ProfessionalNotFoundException y no sigue")
    void shouldFailWhenProfessionalDoesNotExist() {
        when(catalogClient.findProfessional(15L, TOKEN)).thenReturn(Optional.empty());

        assertThrows(ProfessionalNotFoundException.class, () -> useCase(FRIDAY_NOON, 1).getAvailability(query(MONDAY)));

        verify(catalogClient, never()).findSchedules(anyLong(), anyString());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Un profesional deshabilitado lanza ProfessionalDisabledException")
    void shouldFailWhenProfessionalIsDisabled() {
        when(catalogClient.findProfessional(15L, TOKEN)).thenReturn(Optional.of(professional(false)));

        assertThrows(ProfessionalDisabledException.class, () -> useCase(FRIDAY_NOON, 1).getAvailability(query(MONDAY)));

        verify(catalogClient, never()).findSchedules(anyLong(), anyString());
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Si service-catalog no tiene las agendas (404), el profesional no existe")
    void shouldFailWhenSchedulesAreNotFound() {
        when(catalogClient.findProfessional(15L, TOKEN)).thenReturn(Optional.of(professional(true)));
        when(catalogClient.findSchedules(15L, TOKEN)).thenReturn(Optional.empty());

        assertThrows(ProfessionalNotFoundException.class, () -> useCase(FRIDAY_NOON, 1).getAvailability(query(MONDAY)));
    }

    // ---------- validacion ----------

    @Test
    @DisplayName("Datos invalidos: informa cada campo y no llama a ningun servicio")
    void shouldRejectInvalidInput() {
        GetAvailabilityUseCaseImpl useCase = useCase(FRIDAY_NOON, 1);

        InvalidRequestException badId = assertThrows(InvalidRequestException.class,
                () -> useCase.getAvailability(new AvailabilityQuery(0L, MONDAY, TOKEN)));
        InvalidRequestException noDate = assertThrows(InvalidRequestException.class,
                () -> useCase.getAvailability(new AvailabilityQuery(15L, null, TOKEN)));
        InvalidRequestException pastDate = assertThrows(InvalidRequestException.class,
                () -> useCase.getAvailability(query(LocalDate.of(2026, 11, 19))));

        assertTrue(badId.getFieldErrors().containsKey("professionalId"));
        assertTrue(noDate.getFieldErrors().containsKey("date"));
        assertTrue(pastDate.getFieldErrors().containsKey("date"));
        verifyNoInteractions(catalogClient, catedraClient);
    }

    @Test
    @DisplayName("Hoy es una fecha valida")
    void shouldAcceptToday() {
        stubCatalog(schedule(DayOfWeek.FRIDAY, "15:00", "16:00", 30));
        when(catedraClient.getOccupancies(15L, LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20)))
                .thenReturn(List.of());

        Availability availability = useCase(FRIDAY_NOON, 1).getAvailability(query(LocalDate.of(2026, 11, 20)));

        assertEquals(2, availability.slots().size());
    }

    @Test
    @DisplayName("Sin JWT del usuario no se llama a service-catalog")
    void shouldRequireAccessToken() {
        assertThrows(IllegalArgumentException.class,
                () -> useCase(FRIDAY_NOON, 1).getAvailability(new AvailabilityQuery(15L, MONDAY, " ")));

        verifyNoInteractions(catalogClient, catedraClient);
    }

    // ---------- reintentos ----------

    @Test
    @DisplayName("Si una consulta falla por indisponibilidad, se reintenta y se recupera")
    void shouldRetryAnUnavailableRead() {
        when(catalogClient.findProfessional(15L, TOKEN))
                .thenThrow(new CatalogUnavailableException("catalogo caido", null))
                .thenReturn(Optional.of(professional(true)));
        when(catalogClient.findSchedules(15L, TOKEN))
                .thenReturn(Optional.of(List.of(schedule(DayOfWeek.TUESDAY, "09:00", "10:00", 30))));

        Availability availability = useCase(FRIDAY_NOON, 2).getAvailability(query(MONDAY));

        assertTrue(availability.slots().isEmpty());
        verify(catalogClient, times(2)).findProfessional(15L, TOKEN);
    }

    @Test
    @DisplayName("Los reintentos tienen tope: tras agotarlos se informa la indisponibilidad")
    void shouldGiveUpAfterTheMaximumAttempts() {
        when(catalogClient.findProfessional(15L, TOKEN))
                .thenThrow(new CatalogUnavailableException("catalogo caido", null));

        assertThrows(CatalogUnavailableException.class, () -> useCase(FRIDAY_NOON, 2).getAvailability(query(MONDAY)));

        verify(catalogClient, times(2)).findProfessional(15L, TOKEN);
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Con un solo intento no se reintenta")
    void shouldNotRetryWithASingleAttempt() {
        when(catalogClient.findProfessional(15L, TOKEN))
                .thenThrow(new CatalogUnavailableException("catalogo caido", null));

        assertThrows(CatalogUnavailableException.class, () -> useCase(FRIDAY_NOON, 1).getAvailability(query(MONDAY)));

        verify(catalogClient, times(1)).findProfessional(15L, TOKEN);
    }

    @Test
    @DisplayName("Si la catedra no responde tras los reintentos, se informa y no se inventa disponibilidad")
    void shouldPropagateCatedraUnavailable() {
        stubCatalog(schedule(DayOfWeek.MONDAY, "09:00", "10:00", 30));
        when(catedraClient.getOccupancies(15L, MONDAY, MONDAY))
                .thenThrow(new CatedraUnavailableException("catedra caida", false, null));

        assertThrows(CatedraUnavailableException.class, () -> useCase(FRIDAY_NOON, 2).getAvailability(query(MONDAY)));

        verify(catedraClient, times(2)).getOccupancies(15L, MONDAY, MONDAY);
    }

    @Test
    @DisplayName("Un rechazo de service-catalog (por ejemplo, un token invalido) no se reintenta")
    void shouldNotRetryARejection() {
        when(catalogClient.findProfessional(15L, TOKEN))
                .thenThrow(new CatalogRejectedException(401, "UNAUTHORIZED", "token"));

        assertThrows(CatalogRejectedException.class, () -> useCase(FRIDAY_NOON, 3).getAvailability(query(MONDAY)));

        verify(catalogClient, times(1)).findProfessional(15L, TOKEN);
    }
}
