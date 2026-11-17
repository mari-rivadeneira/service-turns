package com.example.hexagonal.turns.infrastructure.client.catalog;

import com.example.hexagonal.turns.application.exception.CatalogRejectedException;
import com.example.hexagonal.turns.application.exception.CatalogUnavailableException;
import com.example.hexagonal.turns.domain.model.CatalogProfessional;
import com.example.hexagonal.turns.domain.model.CatalogSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CatalogRestClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String USER_TOKEN = "jwt-del-usuario";
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    private MockRestServiceServer mockServer;
    private CatalogRestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogRestClient(builder.build(), BASE_URL);
    }

    @Test
    @DisplayName("Consulta un profesional reenviando el JWT del usuario e ignora los campos desconocidos")
    void shouldFindProfessionalForwardingTheUserToken() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/15"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + USER_TOKEN))
                .andRespond(withSuccess("""
                        {"id": 15, "categoryId": 1, "firstName": "Ana", "lastName": "Gomez", "enabled": true,
                         "createdAt": "2026-07-01T12:00:00Z", "updatedAt": "2026-09-10T13:40:00Z", "campoNuevo": "x"}
                        """, MediaType.APPLICATION_JSON));

        Optional<CatalogProfessional> professional = client.findProfessional(15L, USER_TOKEN);

        mockServer.verify();
        assertTrue(professional.isPresent());
        assertEquals(15L, professional.get().id());
        assertEquals(1L, professional.get().categoryId());
        assertEquals("Gomez", professional.get().lastName());
        assertTrue(professional.get().enabled());
    }

    @Test
    @DisplayName("Un profesional deshabilitado se devuelve con enabled en false")
    void shouldReturnDisabledProfessional() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/16"))
                .andRespond(withSuccess("""
                        {"id": 16, "categoryId": 1, "firstName": "Luis", "lastName": "Perez", "enabled": false}
                        """, MediaType.APPLICATION_JSON));

        assertFalse(client.findProfessional(16L, USER_TOKEN).orElseThrow().enabled());
    }

    @Test
    @DisplayName("Un profesional inexistente (404) devuelve vacio, no un error")
    void shouldReturnEmptyWhenProfessionalDoesNotExist() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/999"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM_JSON).body("""
                        {"title": "Not Found", "status": 404, "detail": "Profesional no encontrado: 999",
                         "path": "/api/catalog/professionals/999", "code": "NOT_FOUND"}
                        """));

        assertTrue(client.findProfessional(999L, USER_TOKEN).isEmpty());
    }

    @Test
    @DisplayName("Lee las agendas con horas HH:mm:ss y HH:mm, incluidas las deshabilitadas")
    void shouldFindSchedules() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/15/schedules"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + USER_TOKEN))
                .andRespond(withSuccess("""
                        [{"id": 21, "professionalId": 15, "dayOfWeek": "MONDAY", "startTime": "09:00:00",
                          "endTime": "12:00:00", "slotDurationMinutes": 30, "enabled": true},
                         {"id": 22, "professionalId": 15, "dayOfWeek": "FRIDAY", "startTime": "14:00",
                          "endTime": "18:00", "slotDurationMinutes": 20, "enabled": false}]
                        """, MediaType.APPLICATION_JSON));

        List<CatalogSchedule> schedules = client.findSchedules(15L, USER_TOKEN).orElseThrow();

        assertEquals(2, schedules.size());
        assertEquals(DayOfWeek.MONDAY, schedules.get(0).dayOfWeek());
        assertEquals(LocalTime.of(9, 0), schedules.get(0).startTime());
        assertEquals(30, schedules.get(0).slotDurationMinutes());
        assertTrue(schedules.get(0).enabled());
        assertEquals(LocalTime.of(14, 0), schedules.get(1).startTime());
        assertFalse(schedules.get(1).enabled());
    }

    @Test
    @DisplayName("Un profesional sin agendas devuelve una lista vacia; uno inexistente, vacio")
    void shouldDistinguishEmptySchedulesFromMissingProfessional() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/15/schedules"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/999/schedules"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM_JSON)
                        .body("{\"status\": 404, \"code\": \"NOT_FOUND\"}"));

        assertEquals(Optional.of(List.of()), client.findSchedules(15L, USER_TOKEN));
        assertTrue(client.findSchedules(999L, USER_TOKEN).isEmpty());
    }

    @Test
    @DisplayName("Un token invalido (401) es un rechazo, no un servicio caido")
    void shouldTranslateUnauthorizedAsRejection() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/15"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(PROBLEM_JSON).body("""
                        {"title": "Unauthorized", "status": 401, "code": "UNAUTHORIZED"}
                        """));

        CatalogRejectedException ex = assertThrows(CatalogRejectedException.class,
                () -> client.findProfessional(15L, USER_TOKEN));

        assertEquals(401, ex.getStatus());
        assertEquals("UNAUTHORIZED", ex.getCode());
    }

    @Test
    @DisplayName("Un 400 tambien es un rechazo")
    void shouldTranslateBadRequestAsRejection() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/15"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertEquals(400, assertThrows(CatalogRejectedException.class,
                () -> client.findProfessional(15L, USER_TOKEN)).getStatus());
    }

    @Test
    @DisplayName("Un error del servidor (5xx) es indisponibilidad")
    void shouldTranslateServerErrorAsUnavailable() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/15"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(CatalogUnavailableException.class, () -> client.findProfessional(15L, USER_TOKEN));
    }

    @Test
    @DisplayName("Una falla de red es indisponibilidad")
    void shouldTranslateNetworkFailureAsUnavailable() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/15"))
                .andRespond(withException(new ConnectException("Connection refused")));
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/15/schedules"))
                .andRespond(withException(new IOException("Connection reset")));

        assertThrows(CatalogUnavailableException.class, () -> client.findProfessional(15L, USER_TOKEN));
        assertThrows(CatalogUnavailableException.class, () -> client.findSchedules(15L, USER_TOKEN));
    }

    @Test
    @DisplayName("Una respuesta 200 sin contenido o que no es JSON es indisponibilidad")
    void shouldTranslateInvalidResponseAsUnavailable() {
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/15"))
                .andRespond(withStatus(HttpStatus.OK));
        mockServer.expect(requestTo(BASE_URL + "/api/catalog/professionals/16"))
                .andRespond(withSuccess("esto no es json", MediaType.APPLICATION_JSON));

        assertThrows(CatalogUnavailableException.class, () -> client.findProfessional(15L, USER_TOKEN));
        assertThrows(CatalogUnavailableException.class, () -> client.findProfessional(16L, USER_TOKEN));
    }

    @Test
    @DisplayName("Sin JWT del usuario no se llama a service-catalog; sin URL configurada tampoco")
    void shouldRequireTokenAndConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> client.findProfessional(15L, " "));
        assertThrows(IllegalArgumentException.class, () -> client.findSchedules(15L, null));
        assertThrows(IllegalStateException.class,
                () -> new CatalogRestClient(RestClient.builder().build(), "").findProfessional(15L, USER_TOKEN));
    }
}