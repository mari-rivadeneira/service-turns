package com.example.hexagonal.turns.infrastructure.persistence.mapper;

import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.infrastructure.persistence.entity.ReservationProcessEntity;
import org.springframework.stereotype.Component;

@Component
public class ReservationProcessMapper {

    public ReservationProcess toDomain(ReservationProcessEntity entity) {
        ReservationProcess process = new ReservationProcess();
        process.setId(entity.getId());
        process.setVersion(entity.getVersion() == null ? 0L : entity.getVersion());
        process.setUserId(entity.getUserId());
        process.setExternalPatientId(entity.getExternalPatientId());
        process.setProfessionalId(entity.getProfessionalId());
        process.setProfessionalFirstName(entity.getProfessionalFirstName());
        process.setProfessionalLastName(entity.getProfessionalLastName());
        process.setAppointmentDate(entity.getAppointmentDate());
        process.setStartTime(entity.getStartTime());
        process.setEndTime(entity.getEndTime());
        process.setPatientFirstName(entity.getPatientFirstName());
        process.setPatientLastName(entity.getPatientLastName());
        process.setPatientPhone(entity.getPatientPhone());
        process.setCatedraProcessId(entity.getCatedraProcessId());
        process.setHoldId(entity.getHoldId());
        process.setRequestEventId(entity.getRequestEventId());
        process.setReservationId(entity.getReservationId());
        process.setStatus(entity.getStatus());
        process.setStatusReason(entity.getStatusReason());
        process.setExpiresAt(entity.getExpiresAt());
        process.setCreatedAt(entity.getCreatedAt());
        process.setUpdatedAt(entity.getUpdatedAt());
        process.setConfirmedAt(entity.getConfirmedAt());
        process.setCancelledAt(entity.getCancelledAt());
        return process;
    }

    /** Entidad para actualizar un proceso existente: conserva la version leida, que es la que se compara. */
    public ReservationProcessEntity toEntity(ReservationProcess process) {
        ReservationProcessEntity entity = new ReservationProcessEntity();
        entity.setId(process.getId());
        entity.setVersion(process.getVersion());
        entity.setUserId(process.getUserId());
        entity.setExternalPatientId(process.getExternalPatientId());
        entity.setProfessionalId(process.getProfessionalId());
        entity.setProfessionalFirstName(process.getProfessionalFirstName());
        entity.setProfessionalLastName(process.getProfessionalLastName());
        entity.setAppointmentDate(process.getAppointmentDate());
        entity.setStartTime(process.getStartTime());
        entity.setEndTime(process.getEndTime());
        entity.setPatientFirstName(process.getPatientFirstName());
        entity.setPatientLastName(process.getPatientLastName());
        entity.setPatientPhone(process.getPatientPhone());
        entity.setCatedraProcessId(process.getCatedraProcessId());
        entity.setHoldId(process.getHoldId());
        entity.setRequestEventId(process.getRequestEventId());
        entity.setReservationId(process.getReservationId());
        entity.setStatus(process.getStatus());
        entity.setStatusReason(process.getStatusReason());
        entity.setExpiresAt(process.getExpiresAt());
        entity.setCreatedAt(process.getCreatedAt());
        entity.setUpdatedAt(process.getUpdatedAt());
        entity.setConfirmedAt(process.getConfirmedAt());
        entity.setCancelledAt(process.getCancelledAt());
        return entity;
    }

    /** Entidad para insertar: sin version, que Hibernate inicializa en 0. */
    public ReservationProcessEntity toNewEntity(ReservationProcess process) {
        ReservationProcessEntity entity = toEntity(process);
        entity.setVersion(null);
        return entity;
    }
}
