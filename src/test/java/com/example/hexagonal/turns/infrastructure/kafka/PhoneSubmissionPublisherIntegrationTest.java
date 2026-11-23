package com.example.hexagonal.turns.infrastructure.kafka;

import com.example.hexagonal.turns.domain.model.PhoneSubmission;
import com.example.hexagonal.turns.domain.port.out.PhoneSubmissionPublisher;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Requiere el compose activo (Kafka en el 9095). Usa topics y grupo propios para no interferir con otras pruebas. */
@SpringBootTest(properties = "catedra.group-id=proyecto-test")
@Import(KafkaTestTopicsConfiguration.class)
class PhoneSubmissionPublisherIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("catedra.kafka.appointment-actions-topic", () -> "test.actions." + SUFFIX);
        registry.add("catedra.kafka.appointment-phone-topic", () -> "test.phone." + SUFFIX);
        registry.add("spring.kafka.consumer.group-id", () -> "test-turns-" + SUFFIX);
    }

    @Autowired
    private PhoneSubmissionPublisher publisher;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${catedra.kafka.appointment-phone-topic}")
    private String phoneTopic;

    @Test
    @DisplayName("Publica AdditionalInformationSubmitted con la clave y todos los campos que exige el contrato")
    void shouldPublishTheMessageAsTheContractRequires() {
        publisher.publish(new PhoneSubmission("phone-submitted-it-1", "process-a92b47d0", "hold-6c0c2f41",
                "request-event-8a31", "+5492615551234", Instant.parse("2026-09-10T14:36:00Z")));

        ConsumerRecord<String, String> record = readFirstRecord();

        assertNotNull(record, "no llego ningun mensaje al topic del telefono");
        assertEquals("process-a92b47d0", record.key());
        String json = record.value();
        assertEquals("phone-submitted-it-1", JsonPath.<String>read(json, "$.eventId"));
        assertEquals("AdditionalInformationSubmitted", JsonPath.<String>read(json, "$.eventType"));
        assertEquals("proyecto-test", JsonPath.<String>read(json, "$.groupId"));
        assertEquals("process-a92b47d0", JsonPath.<String>read(json, "$.reservationProcessId"));
        assertEquals("hold-6c0c2f41", JsonPath.<String>read(json, "$.holdId"));
        assertEquals("request-event-8a31", JsonPath.<String>read(json, "$.requestEventId"));
        assertEquals("+5492615551234", JsonPath.<String>read(json, "$.information.phoneNumber"));
        assertEquals("2026-09-10T14:36:00Z", JsonPath.<String>read(json, "$.occurredAt"));
        assertEquals(1, JsonPath.<Integer>read(json, "$.schemaVersion"));
        assertTrue(json.contains("\"information\""));
    }

    private ConsumerRecord<String, String> readFirstRecord() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + SUFFIX);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(phoneTopic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    return record;
                }
            }
        }
        return null;
    }
}
