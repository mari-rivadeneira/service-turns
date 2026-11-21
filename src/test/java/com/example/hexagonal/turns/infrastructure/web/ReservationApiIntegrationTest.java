package com.example.hexagonal.turns.infrastructure.web;

import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.domain.model.CatalogProfessional;
import com.example.hexagonal.turns.domain.model.CatalogSchedule;
import com.example.hexagonal.turns.domain.model.CatedraHold;
import com.example.hexagonal.turns.domain.model.CatedraHoldConfirmation;
import com.example.hexagonal.turns.domain.port.out.CatalogClient;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import com.example.hexagonal.turns.infrastructure.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Casos de uso y base reales (compose activo); solo los clientes de service-catalog y de la catedra se simulan.
 * Los procesos de prueba se borran al terminar cada test.
 */
@SpringBootTest
class ReservationApiIntegrationTest {

    private static final long USER_A = 990_301L;
    private static final long USER_B = 990_302L;
    private static final LocalDate FUTURE_MONDAY =
            LocalDate.now().plusYears(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private static final LocalTime START = LocalTime.of(9, 30);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretKey jwtSecretKey;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private CatedraAppointmentClient catedraClient;

    private MockMvc mockMvc;
    private JwtTestTokens tokens;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tokens = new JwtTestTokens(jwtSecretKey);
        jdbc = new JdbcTemplate(dataSource);
        cleanUp();
        when(catalogClient.findProfessional(eq(15L), anyString()))
                .thenReturn(Optional.of(new CatalogProfessional(15L, 1L, "Ana", "Gomez", true)));
        when(catalogClient.findSchedules(eq(15L), anyString())).thenReturn(Optional.of(List.of(
                new CatalogSchedule(21L, 15L, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(11, 0), 30, true))));
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("delete from reservation_processes where user_id in (?, ?)", USER_A, USER_B);
    }

    // ---------- helpers ----------

    private String tokenOf(long userId) {
        return tokens.valid(userId, "usuario" + userId, "ROLE_USER");
    }

    private String startBody(LocalTime start) {
        return """
                {"professionalId": 15, "date": "%s", "startTime": "%s",
                 "patientFirstName": "Juan", "patientLastName": "Perez"}
                """.formatted(FUTURE_MONDAY, start);
    }

    private ResultActions post(String path, String token, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions postWithoutBody(String path, String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void stubHold() {
        when(catedraClient.createHold(anyLong(), any(), any(), anyString()))
                .thenReturn(new CatedraHold("hold-it-1", "process-it-1", "HELD", Instant.now().plusSeconds(600)));
    }

    private String startReservation(long userId) throws Exception {
        String response = post("/api/reservations", tokenOf(userId), startBody(START))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String statusOf(long userId) {
        return jdbc.queryForObject("select status from reservation_processes where user_id = ?", String.class, userId);
    }

    private long countOf(long userId) {
        return jdbc.queryForObject("select count(*) from reservation_processes where user_id = ?", Long.class, userId);
    }

    // ---------- iniciar ----------

    @Test
    @DisplayName("Inicia la reserva: 201, estado HELD a nombre del usuario del token, sin exponer datos internos de la catedra")
    void shouldStartAReservation() throws Exception {
        stubHold();

        // El cuerpo trae un userId y un externalPatientId ajenos: se ignoran, la identidad sale del token
        post("/api/reservations", tokenOf(USER_A), """
                {"professionalId": 15, "date": "%s", "startTime": "09:30", "patientFirstName": "Juan",
                 "patientLastName": "Perez", "userId": 999, "externalPatientId": "otro-paciente"}
                """.formatted(FUTURE_MONDAY))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.professionalId").value(15))
                .andExpect(jsonPath("$.professionalLastName").value("Gomez"))
                .andExpect(jsonPath("$.date").value(FUTURE_MONDAY.toString()))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.holdId").doesNotExist())
                .andExpect(jsonPath("$.catedraProcessId").doesNotExist())
                .andExpect(jsonPath("$.externalPatientId").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap("select status, user_id, external_patient_id, hold_id, "
                + "catedra_process_id from reservation_processes where user_id = ?", USER_A);
        assertEquals("HELD", row.get("status"));
        assertEquals("user-" + USER_A, row.get("external_patient_id"));
        assertEquals("hold-it-1", row.get("hold_id"));
        assertEquals("process-it-1", row.get("catedra_process_id"));
        assertEquals(0L, countOf(999L));
        verify(catedraClient).createHold(15L, FUTURE_MONDAY, START, "user-" + USER_A);
    }

    @Test
    @DisplayName("Repetir el pedido devuelve la misma reserva (200) y no vuelve a pedir un hold")
    void shouldBeIdempotentWhenStartingTwice() throws Exception {
        stubHold();

        String firstId = startReservation(USER_A);
        String secondResponse = post("/api/reservations", tokenOf(USER_A), startBody(START))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(firstId, JsonPath.<String>read(secondResponse, "$.id"));
        assertEquals(1L, countOf(USER_A));
        verify(catedraClient, times(1)).createHold(anyLong(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Turno ya tomado en la catedra: 409 SLOT_UNAVAILABLE y el proceso queda FAILED")
    void shouldReportSlotUnavailable() throws Exception {
        when(catedraClient.createHold(anyLong(), any(), any(), anyString()))
                .thenThrow(new CatedraRejectedException(409, "SLOT_ALREADY_HELD", "detalle interno"));

        post("/api/reservations", tokenOf(USER_A), startBody(START))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));

        assertEquals("FAILED", statusOf(USER_A));
    }

    @Test
    @DisplayName("Si la catedra no responde al crear el hold: 503 y el proceso queda FAILED, sin repetir el POST")
    void shouldReportServiceUnavailableWhenCatedraIsDown() throws Exception {
        when(catedraClient.createHold(anyLong(), any(), any(), anyString()))
                .thenThrow(new CatedraUnavailableException("timeout de lectura", true, null));

        post("/api/reservations", tokenOf(USER_A), startBody(START))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));

        assertEquals("FAILED", statusOf(USER_A));
        verify(catedraClient, times(1)).createHold(anyLong(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Datos invalidos: 400 con el detalle por campo y no se guarda nada")
    void shouldRejectInvalidData() throws Exception {
        post("/api/reservations", tokenOf(USER_A), """
                {"professionalId": 15, "date": "%s", "startTime": "09:30", "patientFirstName": "J"}
                """.formatted(LocalDate.now().minusDays(3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.date").exists())
                .andExpect(jsonPath("$.fieldErrors.patientFirstName").exists())
                .andExpect(jsonPath("$.fieldErrors.patientLastName").exists());

        assertEquals(0L, countOf(USER_A));
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Un horario que no es un turno de la agenda: 400 sobre startTime")
    void shouldRejectAStartTimeThatIsNotASlot() throws Exception {
        post("/api/reservations", tokenOf(USER_A), startBody(LocalTime.of(9, 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.startTime").exists());

        assertEquals(0L, countOf(USER_A));
        verifyNoInteractions(catedraClient);
    }

    @Test
    @DisplayName("Cuerpo ausente o que no es JSON: 400 BAD_REQUEST")
    void shouldRejectMalformedBodies() throws Exception {
        post("/api/reservations", tokenOf(USER_A), "esto no es json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        postWithoutBody("/api/reservations", tokenOf(USER_A))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("Sin token: 401, y no se llama a ningun servicio")
    void shouldRejectRequestsWithoutToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(startBody(START)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/reservations/" + UUID.randomUUID() + "/confirm"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(catalogClient, catedraClient);
    }

    // ---------- confirmar ----------

    @Test
    @DisplayName("Confirma: 202 con WAITING_FOR_PHONE; repetir devuelve lo mismo sin volver a llamar a la catedra")
    void shouldConfirmAndBeIdempotent() throws Exception {
        stubHold();
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CatedraHoldConfirmation("process-it-1", "WAITING_FOR_PHONE",
                        Instant.now().plusSeconds(600)));
        String id = startReservation(USER_A);

        postWithoutBody("/api/reservations/" + id + "/confirm", tokenOf(USER_A))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"));
        assertEquals("WAITING_FOR_PHONE", statusOf(USER_A));

        postWithoutBody("/api/reservations/" + id + "/confirm", tokenOf(USER_A))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"));

        verify(catedraClient, times(1)).confirmHold("hold-it-1", "user-" + USER_A, "Juan", "Perez");
    }

    @Test
    @DisplayName("Un usuario no puede confirmar la reserva de otro: 404, sin cambios y sin llamar a la catedra")
    void shouldNotAllowConfirmingAnotherUsersReservation() throws Exception {
        stubHold();
        String id = startReservation(USER_A);

        postWithoutBody("/api/reservations/" + id + "/confirm", tokenOf(USER_B))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

        assertEquals("HELD", statusOf(USER_A));
        verify(catedraClient, never()).confirmHold(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Una reserva inexistente o un id con formato invalido: 404 y 400")
    void shouldRejectUnknownReservationAndMalformedId() throws Exception {
        postWithoutBody("/api/reservations/" + UUID.randomUUID() + "/confirm", tokenOf(USER_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        postWithoutBody("/api/reservations/no-es-un-uuid/confirm", tokenOf(USER_A))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("Si la catedra no responde al confirmar: 503, el proceso sigue HELD y reintentar funciona")
    void shouldKeepTheReservationHeldWhenConfirmationFailsAndAllowARetry() throws Exception {
        stubHold();
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CatedraUnavailableException("timeout de lectura", true, null))
                .thenReturn(new CatedraHoldConfirmation("process-it-1", "WAITING_FOR_PHONE",
                        Instant.now().plusSeconds(600)));
        String id = startReservation(USER_A);

        postWithoutBody("/api/reservations/" + id + "/confirm", tokenOf(USER_A))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));
        assertEquals("HELD", statusOf(USER_A));

        postWithoutBody("/api/reservations/" + id + "/confirm", tokenOf(USER_A))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"));
    }

    @Test
    @DisplayName("Hold vencido segun la catedra: 409 HOLD_EXPIRED y el proceso pasa a EXPIRED")
    void shouldMarkTheReservationExpiredWhenTheHoldExpired() throws Exception {
        stubHold();
        when(catedraClient.confirmHold(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CatedraRejectedException(404, "HOLD_EXPIRED", "detalle interno"));
        String id = startReservation(USER_A);

        postWithoutBody("/api/reservations/" + id + "/confirm", tokenOf(USER_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));

        assertEquals("EXPIRED", statusOf(USER_A));
    }
}