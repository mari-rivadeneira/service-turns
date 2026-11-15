package com.example.hexagonal.turns.domain.port.out;

public interface ProcessedEventRepository {

    boolean exists(String eventId);

    /**
     * Registra el evento si no estaba. Es atomico y se puede llamar dentro de la transaccion que aplica el cambio.
     *
     * @return true si se registro ahora; false si ya estaba registrado
     */
    boolean markProcessed(String eventId);
}
