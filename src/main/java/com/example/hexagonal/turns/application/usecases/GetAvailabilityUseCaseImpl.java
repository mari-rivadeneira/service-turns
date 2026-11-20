package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatalogUnavailableException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.application.exception.InvalidRequestException;
import com.example.hexagonal.turns.application.exception.ProfessionalDisabledException;
import com.example.hexagonal.turns.application.exception.ProfessionalNotFoundException;
import com.example.hexagonal.turns.domain.model.Availability;
import com.example.hexagonal.turns.domain.model.AvailableSlot;
import com.example.hexagonal.turns.domain.model.CatalogProfessional;
import com.example.hexagonal.turns.domain.model.CatalogSchedule;
import com.example.hexagonal.turns.domain.model.SlotCalculator;
import com.example.hexagonal.turns.domain.model.SlotOccupancy;
import com.example.hexagonal.turns.domain.port.in.GetAvailabilityUseCase;
import com.example.hexagonal.turns.domain.port.out.CatalogClient;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class GetAvailabilityUseCaseImpl implements GetAvailabilityUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetAvailabilityUseCaseImpl.class);

    private final CatalogClient catalogClient;
    private final CatedraAppointmentClient catedraClient;
    private final Clock clock;
    private final int readMaxAttempts;
    private final long readRetryDelayMs;

    public GetAvailabilityUseCaseImpl(
            CatalogClient catalogClient,
            CatedraAppointmentClient catedraClient,
            Clock clock,
            @Value("${turns.read-retry.max-attempts:2}") int readMaxAttempts,
            @Value("${turns.read-retry.delay-ms:200}") long readRetryDelayMs) {
        this.catalogClient = catalogClient;
        this.catedraClient = catedraClient;
        this.clock = clock;
        this.readMaxAttempts = readMaxAttempts;
        this.readRetryDelayMs = readRetryDelayMs;
    }

    @Override
    public Availability getAvailability(AvailabilityQuery query) {
        LocalDateTime now = LocalDateTime.now(clock);
        validate(query, now.toLocalDate());

        long professionalId = query.professionalId();
        LocalDate date = query.date();
        String token = query.accessToken();

        // Informacion vigente: siempre de service-catalog, nunca de una copia local
        CatalogProfessional professional = retryingRead(() -> catalogClient.findProfessional(professionalId, token))
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));
        if (!professional.enabled()) {
            throw new ProfessionalDisabledException(professionalId);
        }
        List<CatalogSchedule> schedules = retryingRead(() -> catalogClient.findSchedules(professionalId, token))
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        List<AvailableSlot> slots = SlotCalculator.generateSlots(schedules, date);
        if (date.equals(now.toLocalDate())) {
            slots = SlotCalculator.removeStarted(slots, now.toLocalTime());
        }
        if (slots.isEmpty()) {
            // Sin turnos posibles no hace falta consultar a la catedra
            return new Availability(professionalId, date, List.of());
        }

        List<SlotOccupancy> occupancies = retryingRead(() -> catedraClient.getOccupancies(professionalId, date, date));
        return new Availability(professionalId, date, SlotCalculator.removeOccupied(slots, date, occupancies));
    }

    private void validate(AvailabilityQuery query, LocalDate today) {
        if (query.accessToken() == null || query.accessToken().isBlank()) {
            throw new IllegalArgumentException("Falta el JWT del usuario para consultar service-catalog");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (query.professionalId() <= 0) {
            errors.put("professionalId", "debe ser un numero positivo");
        }
        if (query.date() == null) {
            errors.put("date", "es obligatoria");
        } else if (query.date().isBefore(today)) {
            errors.put("date", "no puede ser anterior a hoy");
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }

    /**
     * Las consultas se reintentan de forma acotada ante una indisponibilidad (no tienen efectos). Un rechazo, por
     * ejemplo un token invalido, no se reintenta.
     */
    private <T> T retryingRead(Supplier<T> read) {
        for (int attempt = 1; ; attempt++) {
            try {
                return read.get();
            } catch (CatalogUnavailableException | CatedraUnavailableException e) {
                if (attempt >= readMaxAttempts) {
                    throw e;
                }
                log.warn("Consulta fallida (intento {}/{}): {}", attempt, readMaxAttempts, e.getMessage());
                pause();
            }
        }
    }

    private void pause() {
        if (readRetryDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(readRetryDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}