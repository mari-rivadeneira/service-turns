package com.example.hexagonal.turns.infrastructure.client.catedra;

import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.domain.model.AppointmentQuery;
import com.example.hexagonal.turns.domain.model.CatedraAppointment;
import com.example.hexagonal.turns.domain.model.CatedraAppointmentPage;
import com.example.hexagonal.turns.domain.model.CatedraCancellation;
import com.example.hexagonal.turns.domain.model.CatedraHold;
import com.example.hexagonal.turns.domain.model.CatedraHoldConfirmation;
import com.example.hexagonal.turns.domain.model.SlotOccupancy;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import com.example.hexagonal.turns.infrastructure.client.catedra.dto.CatedraApiDtos.AppointmentResponse;
import com.example.hexagonal.turns.infrastructure.client.catedra.dto.CatedraApiDtos.CancelRequest;
import com.example.hexagonal.turns.infrastructure.client.catedra.dto.CatedraApiDtos.CancelResponse;
import com.example.hexagonal.turns.infrastructure.client.catedra.dto.CatedraApiDtos.ConfirmRequest;
import com.example.hexagonal.turns.infrastructure.client.catedra.dto.CatedraApiDtos.ConfirmResponse;
import com.example.hexagonal.turns.infrastructure.client.catedra.dto.CatedraApiDtos.HoldRequest;
import com.example.hexagonal.turns.infrastructure.client.catedra.dto.CatedraApiDtos.HoldResponse;
import com.example.hexagonal.turns.infrastructure.client.catedra.dto.CatedraApiDtos.OccupanciesResponse;
import com.example.hexagonal.turns.infrastructure.client.catedra.dto.CatedraApiDtos.OccupancyItem;
import com.example.hexagonal.turns.infrastructure.client.support.NetworkErrors;
import com.example.hexagonal.turns.infrastructure.client.support.ProblemResponse;
import com.example.hexagonal.turns.infrastructure.client.support.RestClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/** Cliente REST de la catedra con el JWT tecnico del proyecto. No reintenta nada. */
@Component
public class CatedraAppointmentRestClient implements CatedraAppointmentClient {

    private static final Logger log = LoggerFactory.getLogger(CatedraAppointmentRestClient.class);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_REASON_LENGTH = 500;

    private final RestClient restClient;
    private final String baseUrl;
    private final String token;

    @Autowired
    public CatedraAppointmentRestClient(
            @Value("${catedra.api.base-url:}") String baseUrl,
            @Value("${catedra.api.jwt-token:}") String token,
            @Value("${catedra.api.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${catedra.api.read-timeout-ms:10000}") int readTimeoutMs) {
        this(RestClients.create(connectTimeoutMs, readTimeoutMs), baseUrl, token);
    }

    public CatedraAppointmentRestClient(RestClient restClient, String baseUrl, String token) {
        this.restClient = restClient;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.token = token;
    }

    @Override
    public CatedraHold createHold(long professionalId, LocalDate date, LocalTime startTime, String externalPatientId) {
        URI uri = uri(b -> b.pathSegment("api", "appointment-holds"));
        HoldRequest request = new HoldRequest(professionalId, date.toString(), startTime.format(HH_MM), externalPatientId);

        HoldResponse response = call(HttpMethod.POST, uri, request, HoldResponse.class, true).body();

        return new CatedraHold(response.holdId(), response.reservationProcessId(), response.status(),
                response.expiresAt());
    }

    @Override
    public CatedraHoldConfirmation confirmHold(String holdId, String externalPatientId,
                                               String patientFirstName, String patientLastName) {
        URI uri = uri(b -> b.pathSegment("api", "appointment-holds", holdId, "confirm"));
        ConfirmRequest request = new ConfirmRequest(externalPatientId, patientFirstName, patientLastName);

        ConfirmResponse response = call(HttpMethod.POST, uri, request, ConfirmResponse.class, true).body();

        return new CatedraHoldConfirmation(response.reservationProcessId(), response.status(), response.expiresAt());
    }

    @Override
    public List<SlotOccupancy> getOccupancies(long professionalId, LocalDate from, LocalDate to) {
        URI uri = uri(b -> b.pathSegment("api", "appointment-occupancies")
                .queryParam("professionalId", Long.toString(professionalId))
                .queryParam("from", from.toString())
                .queryParam("to", to.toString()));

        OccupanciesResponse response = call(HttpMethod.GET, uri, null, OccupanciesResponse.class, false).body();

        List<OccupancyItem> items = response.occupancies() == null ? List.of() : response.occupancies();
        // Un estado que no es CONFIRMED se trata como ocupado (HELD): ante la duda, el turno no se ofrece
        return items.stream()
                .map(item -> new SlotOccupancy(item.date(), item.startTime(),
                        "CONFIRMED".equals(item.status()) ? SlotOccupancy.Status.CONFIRMED : SlotOccupancy.Status.HELD,
                        item.expiresAt()))
                .toList();
    }

    @Override
    public CatedraAppointmentPage listAppointments(AppointmentQuery query) {
        URI uri = uri(b -> {
            b.pathSegment("api", "appointments");
            if (query.status() != null) {
                b.queryParam("status", query.status().name());
            }
            if (query.from() != null) {
                b.queryParam("from", query.from().toString());
            }
            if (query.to() != null) {
                b.queryParam("to", query.to().toString());
            }
            if (query.externalPatientId() != null) {
                b.queryParam("externalPatientId", query.externalPatientId());
            }
            b.queryParam("page", Integer.toString(query.page()));
            b.queryParam("size", Integer.toString(query.size()));
        });

        Reply<AppointmentResponse[]> reply = call(HttpMethod.GET, uri, null, AppointmentResponse[].class, false);

        List<CatedraAppointment> content = java.util.Arrays.stream(reply.body()).map(this::toAppointment).toList();
        return new CatedraAppointmentPage(content, parseTotal(reply.headers().getFirst("X-Total-Count"), content.size()));
    }

    @Override
    public CatedraCancellation cancelAppointment(String reservationProcessId, String reason) {
        URI uri = uri(b -> b.pathSegment("api", "appointments", reservationProcessId, "cancel"));
        // El cuerpo es opcional: sin motivo no se envia
        Object body = reason == null || reason.isBlank() ? null : new CancelRequest(truncate(reason));

        CancelResponse response = call(HttpMethod.POST, uri, body, CancelResponse.class, true).body();

        return new CatedraCancellation(response.reservationId(), response.reservationProcessId(), response.status(),
                response.cancelledAt());
    }

    // ---------- llamada, errores y mapeos ----------

    private <T> Reply<T> call(HttpMethod method, URI uri, Object body, Class<T> responseType, boolean hasEffects) {
        try {
            RestClient.RequestBodySpec spec = restClient.method(method).uri(uri)
                    .headers(headers -> headers.setBearerAuth(token));
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }

            Reply<T> reply = spec.exchange((request, response) -> {
                if (response.getStatusCode().isError()) {
                    throw toException(response, hasEffects);
                }
                return new Reply<>(response.bodyTo(responseType), response.getHeaders());
            });

            if (reply == null || reply.body() == null) {
                throw new CatedraUnavailableException("La catedra respondio sin contenido", hasEffects, null);
            }
            return reply;
        } catch (RestClientException e) {
            // Red, timeout o respuesta que no se pudo interpretar
            boolean notDelivered = NetworkErrors.requestWasNotDelivered(e);
            log.warn("Catedra no disponible en {} {}: {}", method, uri.getPath(), e.getMessage());
            throw new CatedraUnavailableException("No se pudo completar la llamada a la catedra",
                    hasEffects && !notDelivered, e);
        }
    }

    private RuntimeException toException(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                                         boolean hasEffects) throws IOException {
        int status = response.getStatusCode().value();
        String code = null;
        String detail = null;
        try {
            ProblemResponse problem = response.bodyTo(ProblemResponse.class);
            if (problem != null) {
                code = problem.code();
                detail = problem.detail();
            }
        } catch (Exception ignored) {
            // El cuerpo no es JSON (por ejemplo los 401 y 403 de Spring Security, o un 503)
        }

        if (status >= 500) {
            log.warn("La catedra respondio con estado {}", status);
            return new CatedraUnavailableException("La catedra respondio con estado " + status, hasEffects, null);
        }
        return new CatedraRejectedException(status, code, detail);
    }

    private CatedraAppointment toAppointment(AppointmentResponse a) {
        return new CatedraAppointment(a.reservationId(), a.reservationProcessId(), a.externalPatientId(),
                a.appointmentDate(), a.startTime(), a.endTime(), a.professionalId(), a.professionalFirstName(),
                a.professionalLastName(), a.patientFirstName(), a.patientLastName(), a.patientPhone(),
                parseStatus(a.status()), a.createdAt(), a.confirmedAt(), a.cancelledAt(), a.cancellationReason());
    }

    private CatedraAppointment.Status parseStatus(String value) {
        try {
            return CatedraAppointment.Status.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new CatedraUnavailableException("La catedra informo un estado de reserva desconocido: " + value,
                    false, e);
        }
    }

    private static long parseTotal(String header, long fallback) {
        if (header == null) {
            return fallback;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String truncate(String reason) {
        return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
    }

    private URI uri(Consumer<UriComponentsBuilder> customizer) {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("La URL de la API de catedra no esta configurada");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("El JWT tecnico de catedra no esta configurado");
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl);
        customizer.accept(builder);
        return builder.build().encode().toUri();
    }

    private record Reply<T>(T body, HttpHeaders headers) {
    }
}