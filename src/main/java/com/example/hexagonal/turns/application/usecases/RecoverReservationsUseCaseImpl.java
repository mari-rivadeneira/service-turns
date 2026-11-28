package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.application.service.ReservationReconciler;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.port.in.RecoverReservationsUseCase;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * NO es transaccional (ver StartReservationUseCaseImpl): hay llamadas HTTP a la catedra. Cada proceso se trata por separado,
 * con su propio bloqueo optimista: dos instancias del servicio no se pisan, y reconciliar es idempotente.
 */
@Service
public class RecoverReservationsUseCaseImpl implements RecoverReservationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecoverReservationsUseCaseImpl.class);
    static final String START_ABANDONED = "START_ABANDONED";

    private final ReservationProcessRepository repository;
    private final ReservationReconciler reconciler;
    private final Clock clock;
    private final long startedTimeoutMs;
    private final int batchSize;

    public RecoverReservationsUseCaseImpl(
            ReservationProcessRepository repository,
            ReservationReconciler reconciler,
            Clock clock,
            @Value("${turns.recovery.started-timeout-ms:300000}") long startedTimeoutMs,
            @Value("${turns.recovery.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.reconciler = reconciler;
        this.clock = clock;
        this.startedTimeoutMs = startedTimeoutMs;
        this.batchSize = batchSize;
    }

    @Override
    public RecoveryReport recover() {
        Instant now = Instant.now(clock);
        int abandoned = 0;
        int reconciled = 0;
        int changed = 0;
        int failures = 0;
        boolean catedraUnavailable = false;

        // 1. Procesos que quedaron en STARTED: la catedra nunca respondio. Si creo un hold sin que lo supieramos, vence solo.
        List<ReservationProcess> abandonedStarts =
                repository.findStartedBefore(now.minusMillis(startedTimeoutMs)).stream().limit(batchSize).toList();
        for (ReservationProcess process : abandonedStarts) {
            try {
                process.failed(START_ABANDONED, now);
                repository.update(process);
                abandoned++;
                log.warn("Reserva {} abandonada en STARTED: se cierra como fallida", process.getId());
            } catch (RuntimeException e) {
                // Por ejemplo, un conflicto de version: el pedido original todavia estaba avanzando
                failures++;
                log.warn("No se pudo cerrar el proceso abandonado {}: {}", process.getId(), e.getClass().getSimpleName());
            }
        }

        // 2. Procesos en curso cuya vigencia ya termino: la catedra decide. Si se perdio un evento, esta consulta lo recupera.
        List<ReservationProcess> expired = repository.findExpirable(now).stream().limit(batchSize).toList();
        for (ReservationProcess process : expired) {
            try {
                ReservationProcess result = reconciler.reconcile(process);
                reconciled++;
                if (result.getStatus() != process.getStatus()) {
                    changed++;
                }
            } catch (CatedraUnavailableException e) {
                // Sin la catedra no se puede decidir: se corta y se reintenta en la proxima corrida
                catedraUnavailable = true;
                log.warn("La catedra no responde: se corta la recuperacion y se reintenta en la proxima corrida");
                break;
            } catch (RuntimeException e) {
                failures++;
                log.warn("No se pudo reconciliar el proceso {}: {}", process.getId(), e.getClass().getSimpleName());
            }
        }

        RecoveryReport report = new RecoveryReport(abandoned, reconciled, changed, failures, catedraUnavailable);
        if (abandoned + reconciled + failures > 0 || catedraUnavailable) {
            log.info("Recuperacion: {} abandonados, {} vencidos consultados ({} cambiaron), {} fallos",
                    abandoned, reconciled, changed, failures);
        }
        return report;
    }
}