package com.example.hexagonal.turns.infrastructure.web;

import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.domain.model.CatedraAppointment;
import com.example.hexagonal.turns.domain.model.CatedraAppointmentPage;
import com.example.hexagonal.turns.domain.model.CatedraCancellation;
import com.example.hexagonal.turns.domain.model.PhoneNumber;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import com.example.hexagonal.turns.infrastructure.security.JwtTestTokens;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Base real (compose activo); el cliente de la catedra se simula. Los procesos de prueba se borran al terminar. */
@SpringBootTest
class ReservationManagementApiIntegrationTest {

    private static final long USER_A = 990_701L;
    private static final long USER_B = 990_702L;
    private static final LocalDate DATE = LocalDate.of(2027, 1, 4);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretKey jwtSecretKey;

    @Autowired
    private ReservationProcessRepository repository;

    @Autowired
    private DataSource dataSource;

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

    private ReservationProcess held(long userId, int hour, String tag, Instant createdAt) {
        Instant now = Instant.now();
        ReservationProcess p = repository.create(ReservationProcess.start(userId, "user-" + userId, 900_001L, "Ana",
                "Gomez", DATE, LocalTime.of(hour, 0), LocalTime.of(hour, 30), "Juan", "Perez", createdAt));
        p.holdCreated("it-m-" + tag, "it-mh-" + tag, now.plusSeconds(600), now);
        return repository.update(p);
    }

    private ReservationProcess submitted(long userId, int hour, String tag) {
        Instant now = Instant.now();
        ReservationProcess p = held(userId, hour, tag, now);
        p.initialConfirmationAccepted(now.plusSeconds(600), now);
        p = repository.update(p);
        p.phoneRequested("it-mreq-" + tag, now.plusSeconds(600), now);
        p = repository.update(p);
        p.phoneSubmitted(PhoneNumber.of("2615551234"), now);
        return repository.update(p);
    }

    private ReservationProcess confirmed(long userId, int hour, String tag, long reservationId) {
        ReservationProcess p = submitted(userId, hour, tag);
        p.confirmed(reservationId, Instant.now());
        return repository.update(p);
    }

    private ResultActions get(String path, String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions post(String path, String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions post(String path, String token, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from reservation_processes where id = ?", String.class, id);
    }

    private CatedraAppointment appointment(String processId, CatedraAppointment.Status status, Long reservationId) {
        return new CatedraAppointment(reservationId, processId, "user-" + USER_A, DATE, LocalTime.of(9, 0),
                LocalTime.of(9, 30), 900_001L, "Ana", "Gomez", "Juan", "Perez", "+542615551234", status,
                Instant.now(), null, null, null);
    }

    // ---------- listar ----------

    @Test
    @DisplayName("Cada usuario lista solo sus reservas, de la mas nueva a la mas vieja, sin datos internos de la catedra")
    void shouldListOnlyOwnReservationsNewestFirst() throws Exception {
        Instant now = Instant.now();
        ReservationProcess oldest = held(USER_A, 9, "old", now.minusSeconds(300));
        ReservationProcess newest = held(USER_A, 10, "new", now.minusSeconds(10));
        held(USER_B, 11, "other", now);

        get("/api/reservations", tokenOf(USER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(oldest.getId().toString()))
                .andExpect(jsonPath("$[0].holdId").doesNotExist())
                .andExpect(jsonPath("$[0].userId").doesNotExist());

        get("/api/reservations", tokenOf(USER_B)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("Filtra por estado; un estado desconocido es un 400 BAD_REQUEST")
    void shouldFilterByStatusAndRejectAnInvalidOne() throws Exception {
        held(USER_A, 9, "h", Instant.now());
        ReservationProcess confirmed = confirmed(USER_A, 10, "c", 9701L);

        get("/api/reservations?status=CONFIRMED", tokenOf(USER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(confirmed.getId().toString()));

        get("/api/reservations?status=ALGO", tokenOf(USER_A))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ---------- cancelar ----------

    @Test
    @DisplayName("Cancela una reserva confirmada: 200 CANCELLED; repetir devuelve lo mismo sin volver a llamar a la catedra")
    void shouldCancelAndBeIdempotent() throws Exception {
        ReservationProcess confirmed = confirmed(USER_A, 9, "c1", 9702L);
        when(catedraClient.cancelAppointment("it-m-c1", "Cambio solicitado"))
                .thenReturn(new CatedraCancellation(9702L, "it-m-c1", "CANCELLED", Instant.now()));
        String path = "/api/reservations/" + confirmed.getId() + "/cancel";

        post(path, tokenOf(USER_A), "{\"reason\": \"Cambio solicitado\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andExpect(jsonPath("$.statusReason").value("Cambio solicitado"));
        assertEquals("CANCELLED", statusOf(confirmed.getId()));

        post(path, tokenOf(USER_A), "{\"reason\": \"Cambio solicitado\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(catedraClient, times(1)).cancelAppointment(anyString(), any());
    }

    @Test
    @DisplayName("El cuerpo es opcional; un motivo demasiado largo es 400 VALIDATION_ERROR; un cuerpo que no es JSON, 400 BAD_REQUEST")
    void shouldAcceptNoBodyAndRejectInvalidOnes() throws Exception {
        ReservationProcess confirmed = confirmed(USER_A, 9, "c2", 9703L);
        String path = "/api/reservations/" + confirmed.getId() + "/cancel";

        post(path, tokenOf(USER_A), "{\"reason\": \"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.reason").exists());
        post(path, tokenOf(USER_A), "esto no es json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(catedraClient);

        when(catedraClient.cancelAppointment(anyString(), isNull()))
                .thenReturn(new CatedraCancellation(9703L, "it-m-c2", "CANCELLED", Instant.now()));
        post(path, tokenOf(USER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("No se cancela la reserva de otro usuario (404) ni una que no esta confirmada (409), sin llamar a la catedra")
    void shouldNotCancelOthersOrUnconfirmedReservations() throws Exception {
        ReservationProcess confirmed = confirmed(USER_A, 9, "c3", 9704L);
        ReservationProcess heldProcess = held(USER_A, 10, "h3", Instant.now());

        post("/api/reservations/" + confirmed.getId() + "/cancel", tokenOf(USER_B))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        post("/api/reservations/" + heldProcess.getId() + "/cancel", tokenOf(USER_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        post("/api/reservations/no-es-un-uuid/cancel", tokenOf(USER_A))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertEquals("CONFIRMED", statusOf(confirmed.getId()));
        verify(catedraClient, never()).cancelAppointment(anyString(), any());
    }

    @Test
    @DisplayName("Si la catedra no responde al cancelar: 503, la reserva sigue CONFIRMED y se puede reintentar")
    void shouldKeepTheReservationConfirmedWhenTheCancellationFails() throws Exception {
        ReservationProcess confirmed = confirmed(USER_A, 9, "c4", 9705L);
        when(catedraClient.cancelAppointment(anyString(), any()))
                .thenThrow(new CatedraUnavailableException("timeout de lectura", true, null))
                .thenReturn(new CatedraCancellation(9705L, "it-m-c4", "CANCELLED", Instant.now()));
        String path = "/api/reservations/" + confirmed.getId() + "/cancel";

        post(path, tokenOf(USER_A))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));
        assertEquals("CONFIRMED", statusOf(confirmed.getId()));

        post(path, tokenOf(USER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ---------- refrescar ----------

    @Test
    @DisplayName("Refrescar: si se perdio el evento de confirmacion, la reserva converge con lo que informa la catedra")
    void shouldRefreshAReservationFromTheCatedra() throws Exception {
        ReservationProcess process = submitted(USER_A, 9, "r1");
        when(catedraClient.listAppointments(any())).thenReturn(new CatedraAppointmentPage(
                List.of(appointment("it-m-r1", CatedraAppointment.Status.CONFIRMED, 9706L)), 1));

        post("/api/reservations/" + process.getId() + "/refresh", tokenOf(USER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reservationId").value(9706))
                .andExpect(jsonPath("$.confirmedAt").exists());

        assertEquals("CONFIRMED", statusOf(process.getId()));
    }

    @Test
    @DisplayName("Refrescar la reserva de otro usuario: 404; si la catedra no responde: 503 y no cambia nada")
    void shouldProtectAndHandleFailuresWhenRefreshing() throws Exception {
        ReservationProcess process = submitted(USER_A, 9, "r2");

        post("/api/reservations/" + process.getId() + "/refresh", tokenOf(USER_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        verify(catedraClient, never()).listAppointments(any());

        when(catedraClient.listAppointments(any()))
                .thenThrow(new CatedraUnavailableException("catedra caida", false, null));
        post("/api/reservations/" + process.getId() + "/refresh", tokenOf(USER_A))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));
        assertEquals("PHONE_SUBMITTED", statusOf(process.getId()));
    }

    @Test
    @DisplayName("Sin token: 401 en los tres endpoints, sin llamar a la catedra")
    void shouldRejectRequestsWithoutToken() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(MockMvcRequestBuilders.get("/api/reservations")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/reservations/" + id + "/cancel"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/reservations/" + id + "/refresh"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(catedraClient);
    }
}