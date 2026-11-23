package com.example.hexagonal.turns.infrastructure.kafka.producer;

import com.example.hexagonal.turns.application.exception.PhonePublishException;
import com.example.hexagonal.turns.domain.model.PhoneSubmission;
import com.example.hexagonal.turns.domain.port.out.PhoneSubmissionPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Publica AdditionalInformationSubmitted (contrato 15.5) y espera la confirmacion del broker. */
@Component
public class KafkaPhoneSubmissionPublisher implements PhoneSubmissionPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPhoneSubmissionPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String groupId;
    private final long sendTimeoutMs;

    @Autowired
    public KafkaPhoneSubmissionPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${catedra.kafka.appointment-phone-topic}") String topic,
            @Value("${catedra.group-id:}") String groupId,
            @Value("${turns.kafka.send-timeout-ms:15000}") long sendTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.groupId = groupId;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Override
    public void publish(PhoneSubmission submission) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalStateException(
                    "Falta CATEDRA_GROUP_ID: es el groupId tecnico de la integracion (campo groupId de integration)");
        }
        String payload = toJson(submission);

        try {
            // La key es el reservationProcessId: los eventos de un mismo proceso conservan el orden
            kafkaTemplate.send(topic, submission.reservationProcessId(), payload)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhonePublishException("Se interrumpio el envio del telefono", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("No se pudo publicar el telefono del proceso {}: {}", submission.reservationProcessId(),
                    e.getClass().getSimpleName());
            throw new PhonePublishException("No se pudo publicar el telefono en Kafka", e);
        }
    }

    private String toJson(PhoneSubmission submission) {
        Map<String, Object> information = new LinkedHashMap<>();
        information.put("phoneNumber", submission.phoneNumber());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", submission.eventId());
        body.put("eventType", "AdditionalInformationSubmitted");
        body.put("groupId", groupId);
        body.put("reservationProcessId", submission.reservationProcessId());
        body.put("holdId", submission.holdId());
        body.put("requestEventId", submission.requestEventId());
        body.put("information", information);
        body.put("occurredAt", submission.occurredAt().truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("schemaVersion", 1);
        return objectMapper.writeValueAsString(body);
    }
}