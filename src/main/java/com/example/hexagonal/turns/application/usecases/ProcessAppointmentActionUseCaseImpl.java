package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;
import com.example.hexagonal.turns.domain.port.in.ApplyAppointmentActionUseCase;
import com.example.hexagonal.turns.domain.port.in.ProcessAppointmentActionUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * NO es transaccional a proposito: un conflicto de version deja rollback-only la transaccion donde ocurre, asi que cada
 * intento tiene que empezar una nueva. Por eso el reintento vive aca, fuera de ApplyAppointmentActionUseCase.
 */
@Service
public class ProcessAppointmentActionUseCaseImpl implements ProcessAppointmentActionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessAppointmentActionUseCaseImpl.class);
    private static final int MAX_CONFLICT_ATTEMPTS = 3;

    private final ApplyAppointmentActionUseCase applyUseCase;

    public ProcessAppointmentActionUseCaseImpl(ApplyAppointmentActionUseCase applyUseCase) {
        this.applyUseCase = applyUseCase;
    }

    @Override
    public ApplyAppointmentActionUseCase.Result process(AppointmentActionEvent event) {
        for (int attempt = 1; ; attempt++) {
            try {
                return applyUseCase.apply(event);
            } catch (ConcurrentUpdateException e) {
                if (attempt >= MAX_CONFLICT_ATTEMPTS) {
                    throw e;
                }
                log.warn("Conflicto de version al aplicar el evento {} (intento {}/{}): se reintenta",
                        event.eventId(), attempt, MAX_CONFLICT_ATTEMPTS);
            }
        }
    }
}