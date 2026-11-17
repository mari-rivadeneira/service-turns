package com.example.hexagonal.turns.infrastructure.client.catedra;

import com.example.hexagonal.turns.application.exception.CatedraErrorCodes;
import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.domain.model.AppointmentQuery;
import com.example.hexagonal.turns.domain.model.CatedraAppointment;
import com.example.hexagonal.turns.domain.model.CatedraAppointmentPage;
import com.example.hexagonal.turns.domain.model.CatedraCancellation;
import com.example.hexagonal.turns.domain.model.CatedraHold;
import com.example.hexagonal.turns.domain.model.CatedraHoldConfirmation;
import com.example.hexagonal.turns.domain.model.SlotOccupancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CatedraAppointmentRestClientTest {

    private static final String BASE_URL = "http://localhost:8090";
    private static final String TOKEN = "test-jwt-tecnico";
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 14);
    private static final LocalTime START = LocalTime.of(9, 30);

    private MockRestServiceServer mockServer;
    private CatedraAppointmentRestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new CatedraAppointmentRestClient(builder.build(), BASE_URL, TOKEN);
    }

    private CatedraHold createHold() {
        return client.createHold(15L, DATE, START, "user-42");
    }

    private String problem(int status, String code) {
        return "{\"title\":\"Error\",\"status\":" + status + ",\"detail\":\"detalle\",\"code\":\"" + code + "\"}";
    }

    // ---------- respuestas correctas ----------

    @Test
    @DisplayName("Crea un hold: envia el pedido del contrato con el JWT tecnico e interpreta la respuesta 201")
    void shouldCreateHold() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.professionalId").value(15))
                .andExpect(jsonPath("$.date").value("2026-09-14"))
                .andExpect(jsonPath("$.startTime").value("09:30"))
                .andExpect(jsonPath("$.externalPatientId").value("user-42"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"holdId": "hold-6c0c2f41", "reservationProcessId": "process-a92b47d0",
                         "status": "HELD", "expiresAt": "2026-09-10T14:44:50Z", "campoNuevo": "ignorado"}
                        """));

        CatedraHold hold = createHold();

        mockServer.verify();
        assertEquals("hold-6c0c2f41", hold.holdId());
        assertEquals("process-a92b47d0", hold.reservationProcessId());
        assertEquals("HELD", hold.status());
        assertEquals(Instant.parse("2026-09-10T14:44:50Z"), hold.expiresAt());
    }

    @Test
    @DisplayName("Confirma inicialmente un hold: responde 202 con WAITING_FOR_PHONE")
    void shouldConfirmHold() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds/hold-6c0c2f41/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(jsonPath("$.externalPatientId").value("user-42"))
                .andExpect(jsonPath("$.patientFirstName").value("Juan"))
                .andExpect(jsonPath("$.patientLastName").value("Perez"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"reservationProcessId": "process-a92b47d0", "status": "WAITING_FOR_PHONE",
                         "expiresAt": "2026-09-10T14:44:50Z"}
                        """));

        CatedraHoldConfirmation confirmation = client.confirmHold("hold-6c0c2f41", "user-42", "Juan", "Perez");

        mockServer.verify();
        assertEquals("process-a92b47d0", confirmation.reservationProcessId());
        assertEquals("WAITING_FOR_PHONE", confirmation.status());
        assertEquals(Instant.parse("2026-09-10T14:44:50Z"), confirmation.expiresAt());
    }

    @Test
    @DisplayName("Lee las ocupaciones: CONFIRMED sin vencimiento y HELD con vencimiento, con horas HH:mm:ss")
    void shouldReadOccupancies() {
        mockServer.expect(requestTo(BASE_URL
                        + "/api/appointment-occupancies?professionalId=15&from=2026-09-14&to=2026-09-14"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess("""
                        {"professionalId": 15, "from": "2026-09-14", "to": "2026-09-14", "occupancies": [
                          {"date": "2026-09-14", "startTime": "09:00:00", "status": "CONFIRMED", "expiresAt": null},
                          {"date": "2026-09-14", "startTime": "09:30:00", "status": "HELD",
                           "expiresAt": "2026-09-10T14:44:50Z"}]}
                        """, MediaType.APPLICATION_JSON));

        List<SlotOccupancy> occupancies = client.getOccupancies(15L, DATE, DATE);

        mockServer.verify();
        assertEquals(2, occupancies.size());
        assertEquals(SlotOccupancy.Status.CONFIRMED, occupancies.get(0).status());
        assertEquals(LocalTime.of(9, 0), occupancies.get(0).startTime());
        assertNull(occupancies.get(0).expiresAt());
        assertEquals(SlotOccupancy.Status.HELD, occupancies.get(1).status());
        assertEquals(Instant.parse("2026-09-10T14:44:50Z"), occupancies.get(1).expiresAt());
    }

    @Test
    @DisplayName("Una lista de ocupaciones vacia es una respuesta valida")
    void shouldAcceptEmptyOccupancies() {
        mockServer.expect(requestTo(BASE_URL
                        + "/api/appointment-occupancies?professionalId=15&from=2026-09-14&to=2026-09-14"))
                .andRespond(withSuccess("""
                        {"professionalId": 15, "from": "2026-09-14", "to": "2026-09-14", "occupancies": []}
                        """, MediaType.APPLICATION_JSON));

        assertTrue(client.getOccupancies(15L, DATE, DATE).isEmpty());
    }

    @Test
    @DisplayName("Lista las reservas con los filtros, e interpreta el total de la cabecera X-Total-Count")
    void shouldListAppointments() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointments?status=CONFIRMED&from=2026-09-01&to=2026-09-30"
                        + "&externalPatientId=user-42&page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"reservationId": 8251, "reservationProcessId": "process-a92b47d0",
                          "externalPatientId": "user-42", "appointmentDate": "2026-09-14",
                          "startTime": "09:30:00", "endTime": "10:00:00", "professionalId": 15,
                          "professionalFirstName": "Ana", "professionalLastName": "Gomez",
                          "patientFirstName": "Juan", "patientLastName": "Perez", "patientPhone": "+5492615551234",
                          "status": "CONFIRMED", "createdAt": "2026-09-10T14:35:00Z",
                          "confirmedAt": "2026-09-10T14:36:00Z", "cancelledAt": null, "cancellationReason": null}]
                        """, MediaType.APPLICATION_JSON).header("X-Total-Count", "1"));

        CatedraAppointmentPage page = client.listAppointments(new AppointmentQuery(
                CatedraAppointment.Status.CONFIRMED, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                "user-42", 0, 20));

        mockServer.verify();
        assertEquals(1, page.totalCount());
        CatedraAppointment appointment = page.content().get(0);
        assertEquals(8251L, appointment.reservationId());
        assertEquals("process-a92b47d0", appointment.reservationProcessId());
        assertEquals(CatedraAppointment.Status.CONFIRMED, appointment.status());
        assertEquals(LocalTime.of(9, 30), appointment.startTime());
        assertEquals("+5492615551234", appointment.patientPhone());
        assertNull(appointment.cancelledAt());
    }

    @Test
    @DisplayName("Sin filtros opcionales, la consulta de reservas solo envia la paginacion")
    void shouldListAppointmentsWithoutOptionalFilters() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointments?page=2&size=5"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        CatedraAppointmentPage page = client.listAppointments(new AppointmentQuery(null, null, null, null, 2, 5));

        assertTrue(page.content().isEmpty());
        assertEquals(0, page.totalCount());
    }

    @Test
    @DisplayName("Cancela con motivo: lo envia en el cuerpo")
    void shouldCancelWithReason() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointments/process-a92b47d0/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reason").value("Cambio solicitado por el usuario"))
                .andRespond(withSuccess("""
                        {"reservationId": 8251, "reservationProcessId": "process-a92b47d0",
                         "status": "CANCELLED", "cancelledAt": "2026-09-11T15:10:00Z"}
                        """, MediaType.APPLICATION_JSON));

        CatedraCancellation cancellation = client.cancelAppointment("process-a92b47d0",
                "Cambio solicitado por el usuario");

        assertEquals(8251L, cancellation.reservationId());
        assertEquals("CANCELLED", cancellation.status());
        assertEquals(Instant.parse("2026-09-11T15:10:00Z"), cancellation.cancelledAt());
    }

    @Test
    @DisplayName("Cancela sin motivo: no envia cuerpo")
    void shouldCancelWithoutReasonAndWithoutBody() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointments/process-a92b47d0/cancel"))
                .andExpect(content().string(""))
                .andRespond(withSuccess("""
                        {"reservationId": 8251, "reservationProcessId": "process-a92b47d0",
                         "status": "CANCELLED", "cancelledAt": "2026-09-11T15:10:00Z"}
                        """, MediaType.APPLICATION_JSON));

        client.cancelAppointment("process-a92b47d0", "  ");

        mockServer.verify();
    }

    // ---------- rechazos firmes ----------

    @ParameterizedTest
    @CsvSource({
            "404, HOLD_EXPIRED",
            "403, HOLD_OWNER_MISMATCH",
            "400, EXTERNAL_PATIENT_MISMATCH",
            "409, RESERVATION_PROCESS_ALREADY_CONFIRMED"
    })
    @DisplayName("Los codigos de rechazo del contrato se traducen a CatedraRejectedException con su status y code")
    void shouldTranslateRejectionsWithTheirCode(int status, String code) {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds/hold-1/confirm"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(PROBLEM_JSON).body(problem(status, code)));

        CatedraRejectedException ex = assertThrows(CatedraRejectedException.class,
                () -> client.confirmHold("hold-1", "user-42", "Juan", "Perez"));

        assertEquals(status, ex.getStatus());
        assertEquals(code, ex.getCode());
        assertTrue(ex.hasCode(code));
    }

    @Test
    @DisplayName("Turno ya tomado al crear el hold: 409 SLOT_ALREADY_HELD")
    void shouldTranslateSlotAlreadyHeld() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(PROBLEM_JSON)
                        .body(problem(409, "SLOT_ALREADY_HELD")));

        CatedraRejectedException ex = assertThrows(CatedraRejectedException.class, this::createHold);

        assertEquals(409, ex.getStatus());
        assertEquals(CatedraErrorCodes.SLOT_ALREADY_HELD, ex.getCode());
    }

    @Test
    @DisplayName("Un 401 sin cuerpo (Spring Security) es un fallo de autenticacion del JWT tecnico, sin code")
    void shouldTreatUnauthorizedWithoutBodyAsAuthenticationFailure() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        CatedraRejectedException ex = assertThrows(CatedraRejectedException.class, this::createHold);

        assertEquals(401, ex.getStatus());
        assertNull(ex.getCode());
        assertTrue(ex.isAuthenticationFailure());
    }

    // ---------- indisponibilidad y resultado incierto ----------

    @Test
    @DisplayName("Un 500 en una operacion con efectos es indisponibilidad con resultado incierto")
    void shouldFlagUnknownOutcomeOnServerErrorWhenOperationHasEffects() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class, this::createHold);

        assertTrue(ex.isOutcomeUnknown());
    }

    @Test
    @DisplayName("Un 503 sin code en una operacion con efectos tambien deja el resultado incierto")
    void shouldFlagUnknownOutcomeOnServiceUnavailableWhenOperationHasEffects() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds/hold-1/confirm"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class,
                () -> client.confirmHold("hold-1", "user-42", "Juan", "Perez"));

        assertTrue(ex.isOutcomeUnknown());
    }

    @Test
    @DisplayName("Un 503 en una consulta no deja resultado incierto: no tiene efectos")
    void shouldNotFlagUnknownOutcomeOnReadOperations() {
        mockServer.expect(requestTo(BASE_URL
                        + "/api/appointment-occupancies?professionalId=15&from=2026-09-14&to=2026-09-14"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class,
                () -> client.getOccupancies(15L, DATE, DATE));

        assertFalse(ex.isOutcomeUnknown());
    }

    @Test
    @DisplayName("Conexion rechazada: el pedido no salio, asi que el resultado no es incierto")
    void shouldNotFlagUnknownOutcomeWhenConnectionIsRefused() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds"))
                .andRespond(withException(new ConnectException("Connection refused")));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class, this::createHold);

        assertFalse(ex.isOutcomeUnknown());
    }

    @Test
    @DisplayName("Timeout al conectar: el pedido no salio")
    void shouldNotFlagUnknownOutcomeOnConnectTimeout() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds"))
                .andRespond(withException(new SocketTimeoutException("Connect timed out")));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class, this::createHold);

        assertFalse(ex.isOutcomeUnknown());
    }

    @Test
    @DisplayName("Timeout de lectura en una operacion con efectos: el pedido pudo ejecutarse, el resultado es incierto")
    void shouldFlagUnknownOutcomeOnReadTimeoutWhenOperationHasEffects() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class, this::createHold);

        assertTrue(ex.isOutcomeUnknown());
    }

    @Test
    @DisplayName("Timeout de lectura en una consulta: no hay efectos que reconciliar")
    void shouldNotFlagUnknownOutcomeOnReadTimeoutOfReadOperations() {
        mockServer.expect(requestTo(BASE_URL
                        + "/api/appointment-occupancies?professionalId=15&from=2026-09-14&to=2026-09-14"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class,
                () -> client.getOccupancies(15L, DATE, DATE));

        assertFalse(ex.isOutcomeUnknown());
    }

    @Test
    @DisplayName("Una respuesta que no es JSON en una operacion con efectos deja el resultado incierto")
    void shouldFlagUnknownOutcomeOnInvalidResponseBody() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("esto no es json"));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class, this::createHold);

        assertTrue(ex.isOutcomeUnknown());
    }

    @Test
    @DisplayName("Una respuesta 201 sin contenido tambien deja el resultado incierto")
    void shouldFlagUnknownOutcomeOnEmptyResponse() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointment-holds"))
                .andRespond(withStatus(HttpStatus.CREATED));

        CatedraUnavailableException ex = assertThrows(CatedraUnavailableException.class, this::createHold);

        assertTrue(ex.isOutcomeUnknown());
    }

    @Test
    @DisplayName("Un estado de reserva desconocido se informa como indisponibilidad")
    void shouldRejectUnknownAppointmentStatus() {
        mockServer.expect(requestTo(BASE_URL + "/api/appointments?page=0&size=20"))
                .andRespond(withSuccess("""
                        [{"reservationId": 1, "reservationProcessId": "p-1", "externalPatientId": "user-42",
                          "appointmentDate": "2026-09-14", "startTime": "09:30:00", "endTime": "10:00:00",
                          "professionalId": 15, "status": "ALGO_NUEVO"}]
                        """, MediaType.APPLICATION_JSON));

        assertThrows(CatedraUnavailableException.class,
                () -> client.listAppointments(new AppointmentQuery(null, null, null, null, 0, 20)));
    }

    // ---------- configuracion ----------

    @Test
    @DisplayName("Sin URL o sin JWT tecnico configurados no se llama a la catedra")
    void shouldRequireConfiguration() {
        CatedraAppointmentRestClient withoutUrl = new CatedraAppointmentRestClient(RestClient.builder().build(), "", TOKEN);
        CatedraAppointmentRestClient withoutToken = new CatedraAppointmentRestClient(RestClient.builder().build(), BASE_URL, " ");

        assertThrows(IllegalStateException.class, () -> withoutUrl.createHold(15L, DATE, START, "user-42"));
        assertThrows(IllegalStateException.class, () -> withoutToken.createHold(15L, DATE, START, "user-42"));
    }
}