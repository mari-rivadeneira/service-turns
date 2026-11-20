package com.example.hexagonal.turns.infrastructure.web;

import com.example.hexagonal.turns.application.exception.CatalogUnavailableException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.domain.model.CatalogProfessional;
import com.example.hexagonal.turns.domain.model.CatalogSchedule;
import com.example.hexagonal.turns.domain.model.SlotOccupancy;
import com.example.hexagonal.turns.domain.port.out.CatalogClient;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import com.example.hexagonal.turns.infrastructure.security.JwtTestTokens;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Los clientes de service-catalog y de la catedra se simulan: se prueba la API, la seguridad y la traduccion de errores. */
@SpringBootTest
class AvailabilityApiIntegrationTest {

    // Un lunes dentro de un anio: siempre es una fecha futura
    private static final LocalDate FUTURE_MONDAY =
            LocalDate.now().plusYears(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretKey jwtSecretKey;

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private CatedraAppointmentClient catedraClient;

    private MockMvc mockMvc;
    private String token;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        token = new JwtTestTokens(jwtSecretKey).valid(42L, "ana", "ROLE_USER");
    }

    private ResultActions getAvailability(String query) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/availability?" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String validQuery() {
        return "professionalId=15&date=" + FUTURE_MONDAY;
    }

    private void stubProfessionalWithMondaySchedule() {
        when(catalogClient.findProfessional(15L, token))
                .thenReturn(Optional.of(new CatalogProfessional(15L, 1L, "Ana", "Gomez", true)));
        when(catalogClient.findSchedules(15L, token)).thenReturn(Optional.of(List.of(
                new CatalogSchedule(21L, 15L, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(11, 0), 30, true))));
    }

    @Test
    @DisplayName("Devuelve los turnos libres: agenda del catalogo menos ocupaciones de la catedra, con el JWT del usuario reenviado")
    void shouldReturnAvailableSlots() throws Exception {
        stubProfessionalWithMondaySchedule();
        when(catedraClient.getOccupancies(15L, FUTURE_MONDAY, FUTURE_MONDAY)).thenReturn(List.of(
                new SlotOccupancy(FUTURE_MONDAY, LocalTime.of(9, 0), SlotOccupancy.Status.CONFIRMED, null),
                new SlotOccupancy(FUTURE_MONDAY, LocalTime.of(10, 0), SlotOccupancy.Status.HELD,
                        Instant.now().plusSeconds(600))));

        getAvailability(validQuery())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.professionalId").value(15))
                .andExpect(jsonPath("$.date").value(FUTURE_MONDAY.toString()))
                .andExpect(jsonPath("$.slots.length()").value(2))
                .andExpect(jsonPath("$.slots[0].startTime").value(Matchers.startsWith("09:30")))
                .andExpect(jsonPath("$.slots[0].endTime").value(Matchers.startsWith("10:00")))
                .andExpect(jsonPath("$.slots[1].startTime").value(Matchers.startsWith("10:30")));

        verify(catalogClient).findProfessional(15L, token);
        verify(catalogClient).findSchedules(15L, token);
    }

    @Test
    @DisplayName("Un dia sin agenda devuelve una lista vacia, sin consultar a la catedra")
    void shouldReturnEmptyListWhenThereIsNoScheduleThatDay() throws Exception {
        when(catalogClient.findProfessional(15L, token))
                .thenReturn(Optional.of(new CatalogProfessional(15L, 1L, "Ana", "Gomez", true)));
        when(catalogClient.findSchedules(15L, token)).thenReturn(Optional.of(List.of(
                new CatalogSchedule(21L, 15L, DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(11, 0), 30, true))));

        getAvailability(validQuery())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(0));

        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Sin token: 401, y no se llama a ningun servicio")
    void shouldRejectRequestsWithoutToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/availability?" + validQuery()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(catalogClient, catedraClient);
    }

    @Test
    @DisplayName("Una fecha pasada es un 400 VALIDATION_ERROR con el detalle del campo")
    void shouldRejectPastDate() throws Exception {
        getAvailability("professionalId=15&date=" + LocalDate.now().minusDays(2))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.date").exists());

        verifyNoInteractions(catalogClient, catedraClient);
    }

    @Test
    @DisplayName("Parametros faltantes o con formato invalido: 400 BAD_REQUEST")
    void shouldRejectMissingOrMalformedParameters() throws Exception {
        for (String query : List.of("professionalId=15", "date=" + FUTURE_MONDAY, "professionalId=abc&date=" + FUTURE_MONDAY,
                "professionalId=15&date=2026-13-45", "professionalId=15&date=manana")) {
            getAvailability(query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        verifyNoInteractions(catalogClient, catedraClient);
    }

    @Test
    @DisplayName("Profesional inexistente: 404 PROFESSIONAL_NOT_FOUND")
    void shouldReturnNotFoundForUnknownProfessional() throws Exception {
        when(catalogClient.findProfessional(15L, token)).thenReturn(Optional.empty());

        getAvailability(validQuery())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_FOUND"));
    }

    @Test
    @DisplayName("Profesional deshabilitado: 409 PROFESSIONAL_DISABLED")
    void shouldReturnConflictForDisabledProfessional() throws Exception {
        when(catalogClient.findProfessional(15L, token))
                .thenReturn(Optional.of(new CatalogProfessional(15L, 1L, "Ana", "Gomez", false)));

        getAvailability(validQuery())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_DISABLED"));
    }

    @Test
    @DisplayName("Si service-catalog no esta disponible: 503 CATALOG_UNAVAILABLE, sin inventar informacion")
    void shouldReturnServiceUnavailableWhenCatalogIsDown() throws Exception {
        when(catalogClient.findProfessional(15L, token))
                .thenThrow(new CatalogUnavailableException("catalogo caido", null));

        getAvailability(validQuery())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));

        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Si la catedra no esta disponible: 503 CATEDRA_UNAVAILABLE")
    void shouldReturnServiceUnavailableWhenCatedraIsDown() throws Exception {
        stubProfessionalWithMondaySchedule();
        when(catedraClient.getOccupancies(15L, FUTURE_MONDAY, FUTURE_MONDAY))
                .thenThrow(new CatedraUnavailableException("catedra caida", false, null));

        getAvailability(validQuery())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));
    }
}
