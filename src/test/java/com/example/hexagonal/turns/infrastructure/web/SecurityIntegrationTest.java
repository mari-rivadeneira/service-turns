package com.example.hexagonal.turns.infrastructure.web;

import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import com.example.hexagonal.turns.infrastructure.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Requiere el compose activo (PostgreSQL). Los procesos de prueba se borran al terminar cada test. */
@SpringBootTest
class SecurityIntegrationTest {

    private static final long USER_A = 990_201L;
    private static final long USER_B = 990_202L;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretKey jwtSecretKey;

    @Autowired
    private ReservationProcessRepository processRepository;

    @Autowired
    private DataSource dataSource;

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

    private ResultActions get(String path, String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private void assertUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    private ReservationProcess createProcessFor(long userId) {
        return processRepository.create(ReservationProcess.start(userId, "user-" + userId, 900_001L, "Ana", "Gomez",
                LocalDate.of(2026, 12, 1), LocalTime.of(9, 0), LocalTime.of(9, 30), "Juan", "Perez", Instant.now()));
    }

    // ---------- rechazos: ninguna ruta es publica ----------

    @Test
    @DisplayName("Sin token, todas las rutas responden 401 en problem+json, incluso las que no existen")
    void shouldRejectRequestsWithoutToken() throws Exception {
        for (String path : List.of("/api/test/whoami", "/api/test/admin-only", "/api/ruta-inexistente")) {
            assertUnauthorized(mockMvc.perform(MockMvcRequestBuilders.get(path)));
        }
    }

    @Test
    @DisplayName("Un token mal formado se rechaza")
    void shouldRejectMalformedToken() throws Exception {
        assertUnauthorized(get("/api/test/whoami", "esto-no-es-un-jwt"));
    }

    @Test
    @DisplayName("Un token vencido se rechaza")
    void shouldRejectExpiredToken() throws Exception {
        assertUnauthorized(get("/api/test/whoami", tokens.expired(42L, "ana", "ROLE_USER")));
    }

    @Test
    @DisplayName("Un token firmado con otra clave se rechaza (tambien el JWT tecnico de la catedra)")
    void shouldRejectTokenSignedWithAnotherKey() throws Exception {
        JwtTestTokens foreign = new JwtTestTokens(JwtTestTokens.keyOf(7));

        assertUnauthorized(get("/api/test/whoami", foreign.valid(42L, "ana", "ROLE_USER")));
    }

    @Test
    @DisplayName("Un token HS256 con la clave correcta se rechaza: solo se acepta HS512")
    void shouldRejectTokenWithAnotherAlgorithm() throws Exception {
        assertUnauthorized(get("/api/test/whoami", tokens.withAlgorithm(MacAlgorithm.HS256, 42L, "ana", "ROLE_USER")));
    }

    @Test
    @DisplayName("Un token sin firma (alg none) se rechaza")
    void shouldRejectUnsignedToken() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString("{\"sub\":\"x\",\"userId\":1}".getBytes(StandardCharsets.UTF_8));

        assertUnauthorized(get("/api/test/whoami", header + "." + payload + "."));
    }

    @Test
    @DisplayName("Un token con el cuerpo alterado se rechaza")
    void shouldRejectTamperedToken() throws Exception {
        String valid = tokens.valid(5L, "ana", "ROLE_USER");
        String[] parts = valid.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String altered = payload.replace("\"userId\":5", "\"userId\":6");
        assertNotEquals(payload, altered, "el test debe alterar realmente el cuerpo del token");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(altered.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertUnauthorized(get("/api/test/whoami", tampered));
    }

    @Test
    @DisplayName("Un token con firma valida pero sin userId no identifica a nadie: 401")
    void shouldRejectValidTokenWithoutUserId() throws Exception {
        assertUnauthorized(get("/api/test/whoami", tokens.withoutUserId("ana", "ROLE_USER")));
    }

    // ---------- identidad: sale del token ----------

    @Test
    @DisplayName("Un token emitido como lo hace service-catalog se acepta y se obtiene la identidad")
    void shouldAcceptTokenIssuedByCatalogAndReadIdentity() throws Exception {
        get("/api/test/whoami", tokens.valid(42L, "ana", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.login").value("ana"))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"));
    }

    @Test
    @DisplayName("La identidad sale del token: un userId enviado en la URL se ignora")
    void shouldIgnoreUserIdSentByTheClient() throws Exception {
        get("/api/test/whoami?userId=999", tokens.valid(42L, "ana", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42));
    }

    // ---------- autorizacion ----------

    @Test
    @DisplayName("Un usuario sin el rol pedido recibe 403; con el rol, 200")
    void shouldForbidWhenRoleIsMissing() throws Exception {
        get("/api/test/admin-only", tokens.valid(42L, "ana", "ROLE_USER"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        get("/api/test/admin-only", tokens.valid(1L, "admin", "ROLE_USER ROLE_ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Cada usuario solo ve sus procesos: el de otro usuario es indistinguible de uno inexistente")
    void shouldHideProcessesOfOtherUsers() throws Exception {
        ReservationProcess processOfA = createProcessFor(USER_A);
        String path = "/api/test/processes/" + processOfA.getId();

        get(path, tokens.valid(USER_A, "ana", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(processOfA.getId().toString()))
                .andExpect(jsonPath("$.userId").value(USER_A))
                .andExpect(jsonPath("$.status").value("STARTED"));

        ResultActions otherUser = get(path, tokens.valid(USER_B, "bea", "ROLE_USER"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        ResultActions missing = get("/api/test/processes/" + java.util.UUID.randomUUID(),
                tokens.valid(USER_B, "bea", "ROLE_USER")).andExpect(status().isNotFound());

        // La respuesta es la misma: no se revela que el proceso existe
        assertEquals(JsonPath.<String>read(body(missing), "$.detail"), JsonPath.<String>read(body(otherUser), "$.detail"));
        assertEquals(JsonPath.<String>read(body(missing), "$.code"), JsonPath.<String>read(body(otherUser), "$.code"));
    }

    // ---------- errores ----------

    @ParameterizedTest
    @CsvSource({
            "state, 409, INVALID_STATE_TRANSITION",
            "concurrent, 409, CONCURRENT_UPDATE",
            "catalog-down, 503, CATALOG_UNAVAILABLE",
            "catedra-down, 503, CATEDRA_UNAVAILABLE",
            "catalog-rejected, 502, CATALOG_ERROR",
            "catedra-rejected, 502, CATEDRA_ERROR"
    })
    @DisplayName("Las excepciones de integracion se traducen a un estado y un code estables, sin exponer el detalle")
    void shouldTranslateIntegrationErrors(String kind, int expectedStatus, String expectedCode) throws Exception {
        ResultActions result = get("/api/test/errors/" + kind, tokens.valid(42L, "ana", "ROLE_USER"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(expectedCode));

        assertFalse(body(result).contains("detalle interno"));
    }

    @Test
    @DisplayName("Un error inesperado responde 500 con un texto generico, sin exponer el mensaje interno")
    void shouldHideInternalErrorDetails() throws Exception {
        ResultActions result = get("/api/test/errors/inesperado", tokens.valid(42L, "ana", "ROLE_USER"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Ocurrio un error inesperado"));

        assertFalse(body(result).contains("secreto-interno"));
    }

    @Test
    @DisplayName("Ruta inexistente, metodo no permitido e id con formato invalido: mismo formato, con code estable")
    void shouldTranslateStandardSpringErrors() throws Exception {
        String token = tokens.valid(42L, "ana", "ROLE_USER");

        get("/api/ruta-inexistente", token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        get("/api/test/processes/no-es-un-uuid", token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}