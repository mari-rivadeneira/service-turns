package com.example.hexagonal.turns.domain.port.out;

import com.example.hexagonal.turns.domain.model.PhoneSubmission;

public interface PhoneSubmissionPublisher {

    /**
     * Publica el telefono y espera la confirmacion del broker, con tiempos acotados.
     *
     * @throws com.example.hexagonal.turns.application.exception.PhonePublishException si no se pudo publicar
     */
    void publish(PhoneSubmission submission);
}
