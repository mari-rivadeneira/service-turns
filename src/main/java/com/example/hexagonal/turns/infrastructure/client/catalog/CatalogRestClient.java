package com.example.hexagonal.turns.infrastructure.client.catalog;

import com.example.hexagonal.turns.application.exception.CatalogRejectedException;
import com.example.hexagonal.turns.application.exception.CatalogUnavailableException;
import com.example.hexagonal.turns.domain.model.CatalogProfessional;
import com.example.hexagonal.turns.domain.model.CatalogSchedule;
import com.example.hexagonal.turns.domain.port.out.CatalogClient;
import com.example.hexagonal.turns.infrastructure.client.catalog.dto.CatalogApiDtos.ProfessionalResponse;
import com.example.hexagonal.turns.infrastructure.client.catalog.dto.CatalogApiDtos.ScheduleResponse;
import com.example.hexagonal.turns.infrastructure.client.support.ProblemResponse;
import com.example.hexagonal.turns.infrastructure.client.support.RestClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Cliente de service-catalog: reenvia el JWT del usuario. Solo hace consultas (GET). */
@Component
public class CatalogRestClient implements CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogRestClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    @Autowired
    public CatalogRestClient(
            @Value("${catalog.api.base-url:}") String baseUrl,
            @Value("${catalog.api.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${catalog.api.read-timeout-ms:5000}") int readTimeoutMs) {
        this(RestClients.create(connectTimeoutMs, readTimeoutMs), baseUrl);
    }

    public CatalogRestClient(RestClient restClient, String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    @Override
    public Optional<CatalogProfessional> findProfessional(long professionalId, String accessToken) {
        URI uri = uri(b -> b.pathSegment("api", "catalog", "professionals", Long.toString(professionalId)));

        return get(uri, accessToken, ProfessionalResponse.class)
                .map(p -> new CatalogProfessional(p.id(), p.categoryId(), p.firstName(), p.lastName(), p.enabled()));
    }

    @Override
    public Optional<List<CatalogSchedule>> findSchedules(long professionalId, String accessToken) {
        URI uri = uri(b -> b.pathSegment("api", "catalog", "professionals", Long.toString(professionalId),
                "schedules"));

        return get(uri, accessToken, ScheduleResponse[].class)
                .map(schedules -> Arrays.stream(schedules)
                        .map(s -> new CatalogSchedule(s.id(), s.professionalId(), s.dayOfWeek(), s.startTime(),
                                s.endTime(), s.slotDurationMinutes(), s.enabled()))
                        .toList());
    }

    /** @return el cuerpo, o vacio si service-catalog responde 404 (el recurso no existe) */
    private <T> Optional<T> get(URI uri, String accessToken, Class<T> type) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Falta el JWT del usuario para consultar service-catalog");
        }
        try {
            return restClient.get().uri(uri).headers(headers -> headers.setBearerAuth(accessToken))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return Optional.<T>empty();
                        }
                        if (response.getStatusCode().isError()) {
                            throw toException(response, status);
                        }
                        T body = response.bodyTo(type);
                        if (body == null) {
                            throw new CatalogUnavailableException("service-catalog respondio sin contenido", null);
                        }
                        return Optional.of(body);
                    });
        } catch (RestClientException e) {
            log.warn("service-catalog no disponible en GET {}: {}", uri.getPath(), e.getMessage());
            throw new CatalogUnavailableException("No se pudo completar la llamada a service-catalog", e);
        }
    }

    private RuntimeException toException(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                                         int status) throws IOException {
        if (status >= 500) {
            log.warn("service-catalog respondio con estado {}", status);
            return new CatalogUnavailableException("service-catalog respondio con estado " + status, null);
        }
        String code = null;
        String detail = null;
        try {
            ProblemResponse problem = response.bodyTo(ProblemResponse.class);
            if (problem != null) {
                code = problem.code();
                detail = problem.detail();
            }
        } catch (Exception ignored) {
            // El cuerpo no es JSON
        }
        return new CatalogRejectedException(status, code, detail);
    }

    private URI uri(Consumer<UriComponentsBuilder> customizer) {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("La URL de service-catalog no esta configurada");
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl);
        customizer.accept(builder);
        return builder.build().encode().toUri();
    }
}
