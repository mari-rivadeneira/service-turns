package com.example.hexagonal.turns.infrastructure.persistence.entity;

import com.example.hexagonal.turns.domain.model.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "reservation_processes")
public class ReservationProcessEntity {

    @Id
    private UUID id;

    /** Bloqueo optimista: null en una entidad nueva, Hibernate lo inicializa en 0 y lo incrementa en cada cambio. */
    @Version
    private Long version;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "external_patient_id", nullable = false, length = 100)
    private String externalPatientId;

    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Column(name = "professional_first_name", length = 100)
    private String professionalFirstName;

    @Column(name = "professional_last_name", length = 100)
    private String professionalLastName;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "patient_first_name", nullable = false, length = 100)
    private String patientFirstName;

    @Column(name = "patient_last_name", nullable = false, length = 100)
    private String patientLastName;

    @Column(name = "patient_phone", length = 20)
    private String patientPhone;

    @Column(name = "catedra_process_id", length = 100)
    private String catedraProcessId;

    @Column(name = "hold_id", length = 100)
    private String holdId;

    @Column(name = "request_event_id", length = 100)
    private String requestEventId;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationStatus status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public ReservationProcessEntity() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getExternalPatientId() { return externalPatientId; }
    public void setExternalPatientId(String externalPatientId) { this.externalPatientId = externalPatientId; }
    public Long getProfessionalId() { return professionalId; }
    public void setProfessionalId(Long professionalId) { this.professionalId = professionalId; }
    public String getProfessionalFirstName() { return professionalFirstName; }
    public void setProfessionalFirstName(String professionalFirstName) { this.professionalFirstName = professionalFirstName; }
    public String getProfessionalLastName() { return professionalLastName; }
    public void setProfessionalLastName(String professionalLastName) { this.professionalLastName = professionalLastName; }
    public LocalDate getAppointmentDate() { return appointmentDate; }
    public void setAppointmentDate(LocalDate appointmentDate) { this.appointmentDate = appointmentDate; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getPatientFirstName() { return patientFirstName; }
    public void setPatientFirstName(String patientFirstName) { this.patientFirstName = patientFirstName; }
    public String getPatientLastName() { return patientLastName; }
    public void setPatientLastName(String patientLastName) { this.patientLastName = patientLastName; }
    public String getPatientPhone() { return patientPhone; }
    public void setPatientPhone(String patientPhone) { this.patientPhone = patientPhone; }
    public String getCatedraProcessId() { return catedraProcessId; }
    public void setCatedraProcessId(String catedraProcessId) { this.catedraProcessId = catedraProcessId; }
    public String getHoldId() { return holdId; }
    public void setHoldId(String holdId) { this.holdId = holdId; }
    public String getRequestEventId() { return requestEventId; }
    public void setRequestEventId(String requestEventId) { this.requestEventId = requestEventId; }
    public Long getReservationId() { return reservationId; }
    public void setReservationId(Long reservationId) { this.reservationId = reservationId; }
    public ReservationStatus getStatus() { return status; }
    public void setStatus(ReservationStatus status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
}
