package com.example.hexagonal.turns.infrastructure.kafka.consumer;

import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;
import com.example.hexagonal.turns.domain.port.in.ApplyAppointmentActionUseCase;
import com.example.hexagonal.turns.domain.port.in.ProcessAppointmentActionUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Spring Kafka arranca el consumidor solo, a partir de @KafkaListener y de las propiedades spring.kafka.*. El offset se
 * confirma recien despues de persistir: si el proceso se cae antes, el mensaje se entrega de nuevo y es idempotente.
 */
@Component
public class AppointmentActionsKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(AppointmentActionsKafkaListener.class);

    private final AppointmentActionEventParser parser;
    private final ProcessAppointmentActionUseCase processUseCase;

    public AppointmentActionsKafkaListener(AppointmentActionEventParser parser,
                                           ProcessAppointmentActionUseCase processUseCase) {
        this.parser = parser;
        this.processUseCase = processUseCase;
    }

    @KafkaListener(topics = "${catedra.kafka.appointment-actions-topic}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        Optional<AppointmentActionEvent> event = parser.parse(record.value());
        if (event.isEmpty()) {
            // Ilegible o incompleto: reintentarlo no lo arregla y bloquearia los mensajes que vienen detras
            log.warn("Mensaje descartado en la particion {}, offset {}", record.partition(), record.offset());
            acknowledgment.acknowledge();
            return;
        }

        // Si falla (por ejemplo, la base caida) se propaga: no se confirma y KafkaConfig reintenta
        ApplyAppointmentActionUseCase.Result result = processUseCase.process(event.get());
        log.info("Evento {} ({}) del proceso {}: {}", event.get().eventId(), event.get().type(),
                event.get().reservationProcessId(), result);
        acknowledgment.acknowledge();
    }
}