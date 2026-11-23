package com.example.hexagonal.turns.infrastructure.kafka;

import com.example.hexagonal.turns.application.exception.PhonePublishException;
import com.example.hexagonal.turns.domain.model.PhoneSubmission;
import com.example.hexagonal.turns.infrastructure.kafka.producer.KafkaPhoneSubmissionPublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** No necesita un broker: prueba el comportamiento cuando Kafka no responde. */
class KafkaPhoneSubmissionPublisherTest {

    private static final PhoneSubmission SUBMISSION = new PhoneSubmission("phone-submitted-1", "process-a92b47d0",
            "hold-6c0c2f41", "request-event-8a31", "+5492615551234", Instant.parse("2026-09-10T14:36:00Z"));

    private KafkaTemplate<String, String> unreachableTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Test
    @DisplayName("Si Kafka no responde, falla con PhonePublishException dentro del tiempo acotado y no se cuelga")
    void shouldFailQuicklyWhenKafkaDoesNotRespond() {
        KafkaPhoneSubmissionPublisher publisher = new KafkaPhoneSubmissionPublisher(unreachableTemplate(),
                JsonMapper.builder().build(), "catedra.turnos.telefono.test", "proyecto-test", 5000);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(15),
                () -> assertThrows(PhonePublishException.class, () -> publisher.publish(SUBMISSION)));
    }

    @Test
    @DisplayName("Sin el groupId tecnico configurado no se publica nada")
    void shouldRequireTheTechnicalGroupId() {
        KafkaPhoneSubmissionPublisher publisher = new KafkaPhoneSubmissionPublisher(unreachableTemplate(),
                JsonMapper.builder().build(), "catedra.turnos.telefono.test", " ", 5000);

        assertThrows(IllegalStateException.class, () -> publisher.publish(SUBMISSION));
    }
}
