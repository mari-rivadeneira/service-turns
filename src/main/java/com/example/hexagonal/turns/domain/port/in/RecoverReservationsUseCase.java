package com.example.hexagonal.turns.domain.port.in;

public interface RecoverReservationsUseCase {

    /**
     * Una corrida de recuperacion, acotada: cierra los procesos STARTED abandonados y reconcilia con la catedra los que vencieron.
     * Nunca lanza por un proceso puntual: cada uno se trata por separado.
     */
    RecoveryReport recover();

    /**
     * @param abandonedStarts      procesos STARTED que se cerraron como fallidos
     * @param reconciled           procesos vencidos que se consultaron a la catedra
     * @param changed              cuantos de esos cambiaron de estado
     * @param failures             procesos que no se pudieron tratar (se reintentan en la proxima corrida)
     * @param catedraUnavailable   la catedra no respondio: se corto la corrida
     */
    record RecoveryReport(int abandonedStarts, int reconciled, int changed, int failures, boolean catedraUnavailable) {
    }
}