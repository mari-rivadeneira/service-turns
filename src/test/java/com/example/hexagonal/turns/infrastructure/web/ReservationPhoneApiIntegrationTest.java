package com.example.hexagonal.turns.infrastructure.web;

import com.example.hexagonal.turns.application.exception.PhonePublishException;
import com.example.hexagonal.turns.domain.model.PhoneSubmission;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.port.out.PhoneSubmissionPublisher;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import com.example.hexagonal.turns.infrastructure.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Base real (compose activo); el publicador de Kafka se simula: se prueba la API, la seguridad y la traduccion de errores. */
@SpringBootTest
class ReservationPhoneApiIntegrationTest {

    private static final long USER_A = 990_601L;
    private static final long USER_B = 990_602L;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretKey jwtSecretKey;

    @Autowired
    private ReservationProcessRepository repository;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private PhoneSubmissionPublisher publisher;

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

    private ReservationProcess heldProcess(long userId) {
        Instant now = Instant.now();
        ReservationProcess p = repository.create(ReservationProcess.start(userId, "user-" + userId, 900_001L, "Ana",
                "Gomez", LocalDate.of(2027, 1, 4), LocalTime.of(9, 0), LocalTime.of(9, 30), "Juan", "Perez", now));
        p.holdCreated("it-api-p" + userId, "it-api-h" + userId, now.plusSeconds(600), now);
        return repository.update(p);
    }

    private ReservationProcess requestedProcess(long userId) {
        Instant now = Instant.now();
        ReservationProcess p = heldProcess(userId);
        p.initialConfirmationAccepted(now.plusSeconds(600), now);
        p = repository.update(p);
        p.phoneRequested("it-api-req" + userId, now.plusSeconds(600), now);
        return repository.update(p);
    }

    private ResultActions postPhone(UUID id, String token, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/reservations/" + id + "/phone")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions get(String id, String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/reservations/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String statusOf(long userId) {
        return jdbc.queryForObject("select status from reservation_processes where user_id = ?", String.class, userId);
    }

    // ---------- enviar el telefono ----------

    @Test
    @DisplayName("Envia el telefono: 202 PHONE_SUBMITTED, publica el mensaje del contrato y no devuelve el telefono")
    void shouldSubmitThePhone() throws Exception {
        ReservationProcess process = requestedProcess(USER_A);

        postPhone(process.getId(), tokenOf(USER_A), "{\"phoneNumber\": \"+54 (261) 555-1234\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(process.getId().toString()))
                .andExpect(jsonPath("$.status").value("PHONE_SUBMITTED"))
                .andExpect(jsonPath("$.phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.patientPhone").doesNotExist());

        ArgumentCaptor<PhoneSubmission> captor = ArgumentCaptor.forClass(PhoneSubmission.class);
        verify(publisher).publish(captor.capture());
        assertEquals("it-api-p" + USER_A, captor.getValue().reservationProcessId());
        assertEquals("it-api-h" + USER_A, captor.getValue().holdId());
        assertEquals("it-api-req" + USER_A, captor.getValue().requestEventId());
        assertEquals("+542615551234", captor.getValue().phoneNumber());
        assertEquals("PHONE_SUBMITTED", statusOf(USER_A));
        assertEquals("+542615551234",
                jdbc.queryForObject("select patient_phone from reservation_processes where user_id = ?",
                        String.class, USER_A));
    }

    @Test
    @DisplayName("Un telefono invalido o ausente: 400 VALIDATION_ERROR sobre phoneNumber, sin publicar")
    void shouldRejectInvalidPhones() throws Exception {
        ReservationProcess process = requestedProcess(USER_A);

        postPhone(process.getId(), tokenOf(USER_A), "{\"phoneNumber\": \"abc\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.phoneNumber").exists());
        postPhone(process.getId(), tokenOf(USER_A), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.phoneNumber").exists());

        verifyNoInteractions(publisher);
        assertEquals("PHONE_REQUESTED", statusOf(USER_A));
    }

    @Test
    @DisplayName("Si la catedra todavia no pidio el telefono: 409 INVALID_STATE_TRANSITION, sin publicar")
    void shouldReturnConflictWhenThePhoneWasNotRequested() throws Exception {
        ReservationProcess process = heldProcess(USER_A);

        postPhone(process.getId(), tokenOf(USER_A), "{\"phoneNumber\": \"2615551234\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Si no se puede publicar: 503 KAFKA_UNAVAILABLE y el estado no cambia")
    void shouldReturnServiceUnavailableWhenPublishingFails() throws Exception {
        ReservationProcess process = requestedProcess(USER_A);
        doThrow(new PhonePublishException("Kafka caido", null)).when(publisher).publish(any());

        postPhone(process.getId(), tokenOf(USER_A), "{\"phoneNumber\": \"2615551234\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KAFKA_UNAVAILABLE"));

        assertEquals("PHONE_REQUESTED", statusOf(USER_A));
    }

    @Test
    @DisplayName("Un usuario no puede enviar el telefono de la reserva de otro: 404, sin publicar")
    void shouldNotAllowAnotherUserToSubmit() throws Exception {
        ReservationProcess process = requestedProcess(USER_A);

        postPhone(process.getId(), tokenOf(USER_B), "{\"phoneNumber\": \"2615551234\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

        verifyNoInteractions(publisher);
        assertEquals("PHONE_REQUESTED", statusOf(USER_A));
    }

    @Test
    @DisplayName("Sin token: 401. Cuerpo que no es JSON o id invalido: 400 BAD_REQUEST")
    void shouldRejectUnauthenticatedAndMalformedRequests() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/reservations/" + UUID.randomUUID() + "/phone")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\": \"2615551234\"}"))
                .andExpect(status().isUnauthorized());

        postPhone(UUID.randomUUID(), tokenOf(USER_A), "esto no es json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/reservations/no-es-un-uuid/phone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(USER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\": \"2615551234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(publisher);
    }

    // ---------- consultar ----------

    @Test
    @DisplayName("El dueno consulta su reserva; la de otro usuario, una inexistente y un id invalido responden como corresponde")
    void shouldReturnTheReservationOnlyToItsOwner() throws Exception {
        ReservationProcess process = requestedProcess(USER_A);

        get(process.getId().toString(), tokenOf(USER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(process.getId().toString()))
                .andExpect(jsonPath("$.status").value("PHONE_REQUESTED"))
                .andExpect(jsonPath("$.professionalLastName").value("Gomez"))
                .andExpect(jsonPath("$.holdId").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());

        get(process.getId().toString(), tokenOf(USER_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        get(UUID.randomUUID().toString(), tokenOf(USER_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        get("no-es-un-uuid", tokenOf(USER_A))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/reservations/" + process.getId()))
                .andExpect(status().isUnauthorized());
    }
}