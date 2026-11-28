package com.example.hexagonal.turns.infrastructure.scheduling;

import com.example.hexagonal.turns.domain.port.in.RecoverReservationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spring llama a estos metodos: al terminar el arranque (retoma lo que quedo pendiente antes de un reinicio) y cada intervalo.
 * Solo existe si turns.recovery.enabled es true. Toda la logica esta en el caso de uso.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "turns.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationRecoveryScheduler.class);

    private final RecoverReservationsUseCase recoverReservationsUseCase;

    public ReservationRecoveryScheduler(RecoverReservationsUseCase recoverReservationsUseCase) {
        this.recoverReservationsUseCase = recoverReservationsUseCase;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        run("arranque");
    }

    /** fixedDelay: la proxima corrida cuenta desde que termino la anterior, asi dos nunca se pisan. */
    @Scheduled(fixedDelayString = "${turns.recovery.interval-ms:60000}",
            initialDelayString = "${turns.recovery.interval-ms:60000}")
    public void recoverPeriodically() {
        run("periodica");
    }

    private void run(String origin) {
        try {
            recoverReservationsUseCase.recover();
        } catch (RuntimeException e) {
            // Nunca debe tirar abajo el hilo del scheduler: se registra y se reintenta en la proxima corrida
            log.error("La recuperacion ({}) fallo", origin, e);
        }
    }
}