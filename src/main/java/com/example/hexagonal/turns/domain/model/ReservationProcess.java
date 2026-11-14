package com.example.hexagonal.turns.domain.model;

import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Proceso de reserva de un usuario: desde que inicia hasta que queda confirmado, cancelado, vencido, invalido o fallido.
 * Una reserva confirmada es este mismo proceso en estado CONFIRMED.
 *
 * El estado solo cambia con los metodos de transicion, que respetan la maquina de estados (ReservationStatus) y no
 * modifican nada si la transicion no esta permitida. Los setters existen para que el adaptador de persistencia
 * reconstruya el objeto; el codigo de negocio no debe usarlos.
 */
public class ReservationProcess {

    private static final int MAX_REASON_LENGTH = 500;

    private UUID id;
    private long version;
    private Long userId;
    private String externalPatientId;
    private Long professionalId;
    // Datos descriptivos historicos del profesional: no se usan como catalogo vigente
    private String professionalFirstName;
    private String professionalLastName;
    private LocalDate appointmentDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String patientFirstName;
    private String patientLastName;
    private String patientPhone;
    private String catedraProcessId;
    private String holdId;
    private String requestEventId;
    private Long reservationId;
    private ReservationStatus status;
    private String statusReason;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;
    private Instant cancelledAt;

    public ReservationProcess() {
    }

    /** Registra la intencion de reservar, antes de llamar a la catedra. */
    public static ReservationProcess start(Long userId, String externalPatientId, Long professionalId,
                                           String professionalFirstName, String professionalLastName,
                                           LocalDate appointmentDate, LocalTime startTime, LocalTime endTime,
                                           String patientFirstName, String patientLastName, Instant now) {
        requireNotNull(userId, "userId");
        requireLength(externalPatientId, "externalPatientId", 1, 100);
        requireNotNull(professionalId, "professionalId");
        requireNotNull(appointmentDate, "appointmentDate");
        requireNotNull(startTime, "startTime");
        if (endTime != null && !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("endTime debe ser posterior a startTime");
        }
        requireLength(patientFirstName, "patientFirstName", 2, 100);
        requireLength(patientLastName, "patientLastName", 2, 100);
        requireNotNull(now, "now");

        ReservationProcess process = new ReservationProcess();
        process.id = UUID.randomUUID();
        process.userId = userId;
        process.externalPatientId = externalPatientId;
        process.professionalId = professionalId;
        process.professionalFirstName = professionalFirstName;
        process.professionalLastName = professionalLastName;
        process.appointmentDate = appointmentDate;
        process.startTime = startTime;
        process.endTime = endTime;
        process.patientFirstName = patientFirstName;
        process.patientLastName = patientLastName;
        process.status = ReservationStatus.STARTED;
        process.createdAt = now;
        process.updatedAt = now;
        return process;
    }

    // ---------- transiciones ----------

    /** La catedra creo el hold (REST 201). */
    public void holdCreated(String catedraProcessId, String holdId, Instant expiresAt, Instant now) {
        requireLength(catedraProcessId, "catedraProcessId", 1, 100);
        requireLength(holdId, "holdId", 1, 100);
        requireNotNull(expiresAt, "expiresAt");
        transitionTo(ReservationStatus.HELD, null, now);
        this.catedraProcessId = catedraProcessId;
        this.holdId = holdId;
        this.expiresAt = expiresAt;
    }

    /** La catedra acepto la confirmacion inicial (REST 202). */
    public void initialConfirmationAccepted(Instant expiresAt, Instant now) {
        transitionTo(ReservationStatus.WAITING_FOR_PHONE, null, now);
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
    }

    /** Llego AdditionalInformationRequested: se guarda su eventId, que hay que devolver al enviar el telefono. */
    public void phoneRequested(String requestEventId, Instant expiresAt, Instant now) {
        requireLength(requestEventId, "requestEventId", 1, 100);
        transitionTo(ReservationStatus.PHONE_REQUESTED, null, now);
        this.requestEventId = requestEventId;
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
    }

    /** El usuario informo su telefono (primer envio o reenvio tras un rechazo). */
    public void phoneSubmitted(PhoneNumber phone, Instant now) {
        requireNotNull(phone, "phone");
        transitionTo(ReservationStatus.PHONE_SUBMITTED, null, now);
        this.patientPhone = phone.value();
    }

    /** La catedra rechazo el telefono: el proceso sigue vigente hasta expiresAt. */
    public void phoneRejected(String reason, Instant expiresAt, Instant now) {
        transitionTo(ReservationStatus.PHONE_REJECTED, reason, now);
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
    }

    public void confirmed(Long reservationId, Instant now) {
        requireNotNull(reservationId, "reservationId");
        transitionTo(ReservationStatus.CONFIRMED, null, now);
        this.reservationId = reservationId;
        this.confirmedAt = now;
    }

    public void expired(String reason, Instant now) {
        transitionTo(ReservationStatus.EXPIRED, reason, now);
    }

    public void invalidated(String reason, Instant now) {
        transitionTo(ReservationStatus.INVALID, reason, now);
    }

    public void failed(String reason, Instant now) {
        transitionTo(ReservationStatus.FAILED, reason, now);
    }

    /** Solo se cancelan reservas confirmadas. */
    public void cancelled(String reason, Instant now) {
        transitionTo(ReservationStatus.CANCELLED, reason, now);
        this.cancelledAt = now;
    }

    // ---------- consultas ----------

    /** Para decidir si un evento aplica: si no, es tardio o repetido y se ignora. */
    public boolean canTransitionTo(ReservationStatus target) {
        return status != null && status.canTransitionTo(target);
    }

    /** Vencio su vigencia y todavia puede pasar a EXPIRED (no esta confirmado ni en un estado final). */
    public boolean canExpire(Instant now) {
        return canTransitionTo(ReservationStatus.EXPIRED) && expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isOwnedBy(Long candidateUserId) {
        return userId != null && userId.equals(candidateUserId);
    }

    public boolean isFinal() {
        return status != null && status.isFinal();
    }

    // ---------- internos ----------

    private void transitionTo(ReservationStatus target, String reason, Instant now) {
        requireNotNull(now, "now");
        if (status == null || !status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
        this.status = target;
        this.statusReason = truncate(reason);
        this.updatedAt = now;
    }

    private static String truncate(String reason) {
        return reason != null && reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
    }

    private static void requireNotNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
    }

    private static void requireLength(String value, String field, int min, int max) {
        if (value == null || value.isBlank() || value.length() < min || value.length() > max) {
            throw new IllegalArgumentException(field + " debe tener entre " + min + " y " + max + " caracteres");
        }
    }

    // ---------- getters y setters (los setters son para la persistencia) ----------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
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