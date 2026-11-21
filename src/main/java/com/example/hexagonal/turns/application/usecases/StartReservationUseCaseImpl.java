package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatedraErrorCodes;
import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.application.exception.InvalidRequestException;
import com.example.hexagonal.turns.application.exception.ProfessionalDisabledException;
import com.example.hexagonal.turns.application.exception.ProfessionalNotFoundException;
import com.example.hexagonal.turns.application.exception.ReservationInProgressException;
import com.example.hexagonal.turns.application.exception.SlotUnavailableException;
import com.example.hexagonal.turns.domain.model.AvailableSlot;
import com.example.hexagonal.turns.domain.model.CatalogProfessional;
import com.example.hexagonal.turns.domain.model.CatalogSchedule;
import com.example.hexagonal.turns.domain.model.CatedraHold;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.model.SlotCalculator;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase;
import com.example.hexagonal.turns.domain.port.out.CatalogClient;
import com.example.hexagonal.turns.domain.port.out.CatedraAppointmentClient;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NO es transaccional a proposito: entre una escritura y otra hay llamadas HTTP, y no debe haber una transaccion de base
 * abierta mientras se espera a la catedra. Cada escritura del repositorio confirma por su cuenta.
 *
 * El orden es lo que hace recuperable el proceso: primero se guarda la intencion (STARTED), despues se llama a la catedra
 * y recien entonces se guarda el hold. El POST a la catedra nunca se reintenta solo: ante un resultado incierto se
 * registra el fallo y el hold huerfano vence por su propia vigencia.
 */
@Service
public class StartReservationUseCaseImpl implements StartReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(StartReservationUseCaseImpl.class);

    static final String HOLD_OUTCOME_UNKNOWN = "HOLD_OUTCOME_UNKNOWN";
    static final String CATEDRA_UNAVAILABLE = "CATEDRA_UNAVAILABLE";
    static final String INVALID_HOLD_RESPONSE = "INVALID_HOLD_RESPONSE";
    private static final int MIN_NAME_LENGTH = 2;
    private static final int MAX_NAME_LENGTH = 100;

    private final CatalogClient catalogClient;
    private final CatedraAppointmentClient catedraClient;
    private final ReservationProcessRepository repository;
    private final Clock clock;

    public StartReservationUseCaseImpl(CatalogClient catalogClient, CatedraAppointmentClient catedraClient,
                                       ReservationProcessRepository repository, Clock clock) {
        this.catalogClient = catalogClient;
        this.catedraClient = catedraClient;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public StartReservationResult start(StartReservationCommand command) {
        LocalDateTime localNow = LocalDateTime.now(clock);
        validate(command, localNow.toLocalDate());

        long userId = command.userId();
        long professionalId = command.professionalId();
        LocalDate date = command.date();

        // 1. Idempotencia: repetir el pedido (por un timeout, por ejemplo) no duplica la reserva
        Optional<ReservationProcess> existing =
                repository.findActiveByUserAndSlot(userId, professionalId, date, command.startTime());
        if (existing.isPresent()) {
            if (existing.get().getStatus() == ReservationStatus.STARTED) {
                throw new ReservationInProgressException();
            }
            return new StartReservationResult(existing.get(), false);
        }

        // 2. Informacion vigente: siempre de service-catalog. Todavia no se guardo nada.
        CatalogProfessional professional = catalogClient.findProfessional(professionalId, command.accessToken())
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));
        if (!professional.enabled()) {
            throw new ProfessionalDisabledException(professionalId);
        }
        List<CatalogSchedule> schedules = catalogClient.findSchedules(professionalId, command.accessToken())
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        AvailableSlot slot = SlotCalculator.generateSlots(schedules, date).stream()
                .filter(candidate -> candidate.startTime().equals(command.startTime()))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException(
                        Map.of("startTime", "no es un turno valido para ese dia")));
        if (date.equals(localNow.toLocalDate()) && !slot.startTime().isAfter(localNow.toLocalTime())) {
            throw new InvalidRequestException(Map.of("startTime", "el turno ya empezo"));
        }

        // 3. Escritura previa: la intencion queda guardada ANTES de llamar a la catedra
        ReservationProcess process = repository.create(ReservationProcess.start(
                userId, "user-" + userId, professionalId, professional.firstName(), professional.lastName(),
                date, slot.startTime(), slot.endTime(),
                command.patientFirstName().trim(), command.patientLastName().trim(), Instant.now(clock)));

        // 4. Hold en la catedra. Un POST no se reintenta: se interpreta el resultado.
        CatedraHold hold;
        try {
            hold = catedraClient.createHold(professionalId, date, slot.startTime(), process.getExternalPatientId());
        } catch (CatedraRejectedException e) {
            // Rechazo firme: la catedra no creo nada
            failProcess(process, e.getCode() != null ? e.getCode() : "CATEDRA_REJECTED_" + e.getStatus());
            throw translateRejection(e, professionalId);
        } catch (CatedraUnavailableException e) {
            // Si el pedido pudo llegar, el hold pudo crearse sin que lo sepamos: no hay forma de usarlo ni de repetirlo
            // a ciegas. El hold huerfano vence solo.
            failProcess(process, e.isOutcomeUnknown() ? HOLD_OUTCOME_UNKNOWN : CATEDRA_UNAVAILABLE);
            throw e;
        }

        if (hold == null || isBlank(hold.holdId()) || isBlank(hold.reservationProcessId())
                || hold.expiresAt() == null) {
            failProcess(process, INVALID_HOLD_RESPONSE);
            throw new CatedraUnavailableException("La catedra respondio un hold incompleto", true, null);
        }

        // 5. Se guarda el hold. Si el servicio se cae antes de este paso, el proceso queda STARTED y la recuperacion lo
        // resuelve por tiempo.
        process.holdCreated(hold.reservationProcessId(), hold.holdId(), hold.expiresAt(), Instant.now(clock));
        ReservationProcess held = repository.update(process);
        log.info("Reserva {} iniciada: hold creado, vence {}", held.getId(), held.getExpiresAt());
        return new StartReservationResult(held, true);
    }

    /** Mejor esfuerzo: si tampoco se puede guardar el fallo, queda STARTED y la recuperacion lo resolvera. */
    private void failProcess(ReservationProcess process, String reason) {
        try {
            process.failed(reason, Instant.now(clock));
            repository.update(process);
            log.warn("Reserva {} fallida al crear el hold: {}", process.getId(), reason);
        } catch (RuntimeException e) {
            log.error("No se pudo marcar como fallido el proceso {}: queda STARTED y la recuperacion lo resolvera",
                    process.getId(), e);
        }
    }

    private RuntimeException translateRejection(CatedraRejectedException e, long professionalId) {
        String code = e.getCode();
        if (CatedraErrorCodes.SLOT_ALREADY_HELD.equals(code) || CatedraErrorCodes.SLOT_ALREADY_RESERVED.equals(code)) {
            return new SlotUnavailableException();
        }
        if (CatedraErrorCodes.PROFESSIONAL_DISABLED.equals(code)) {
            return new ProfessionalDisabledException(professionalId);
        }
        if (CatedraErrorCodes.INVALID_SLOT.equals(code)) {
            return new InvalidRequestException(Map.of("startTime", "la catedra no reconoce ese turno"));
        }
        return e;
    }

    private void validate(StartReservationCommand command, LocalDate today) {
        if (command.accessToken() == null || command.accessToken().isBlank()) {
            throw new IllegalArgumentException("Falta el JWT del usuario para consultar service-catalog");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (command.professionalId() == null || command.professionalId() <= 0) {
            errors.put("professionalId", "debe ser un numero positivo");
        }
        if (command.date() == null) {
            errors.put("date", "es obligatoria");
        } else if (command.date().isBefore(today)) {
            errors.put("date", "no puede ser anterior a hoy");
        }
        if (command.startTime() == null) {
            errors.put("startTime", "es obligatoria");
        }
        validateName(errors, "patientFirstName", command.patientFirstName());
        validateName(errors, "patientLastName", command.patientLastName());
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }

    private void validateName(Map<String, String> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.put(field, "es obligatorio");
        } else if (value.trim().length() < MIN_NAME_LENGTH || value.trim().length() > MAX_NAME_LENGTH) {
            errors.put(field, "debe tener entre " + MIN_NAME_LENGTH + " y " + MAX_NAME_LENGTH + " caracteres");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
