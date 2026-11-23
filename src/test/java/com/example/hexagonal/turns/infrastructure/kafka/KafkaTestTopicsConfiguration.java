package com.example.hexagonal.turns.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/** Crea los topics de prueba antes de que arranque el listener (KafkaAdmin los crea al iniciar el contexto). */
@TestConfiguration
class KafkaTestTopicsConfiguration {

    @Bean
    NewTopic appointmentActionsTestTopic(@Value("${catedra.kafka.appointment-actions-topic}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic appointmentPhoneTestTopic(@Value("${catedra.kafka.appointment-phone-topic}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}