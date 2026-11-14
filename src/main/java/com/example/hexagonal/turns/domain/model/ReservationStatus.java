package com.example.hexagonal.turns.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estados del proceso de reserva y transiciones permitidas. Cualquier transicion que no figure aca se rechaza.
 * Los estados finales (EXPIRED, INVALID, CANCELLED y FAILED) no tienen salida; CONFIRMED solo admite CANCELLED.
 */
public enum ReservationStatus {

    /** Intencion registrada localmente; todavia no hay hold en la catedra. */
    STARTED,
    /** Hold temporal creado en la catedra (REST 201). */
    HELD,
    /** Confirmacion inicial aceptada (REST 202); falta el evento AdditionalInformationRequested. */
    WAITING_FOR_PHONE,
    /** La catedra pidio el telefono (AdditionalInformationRequested): se puede enviar. */
    PHONE_REQUESTED,
    /** Telefono enviado (AdditionalInformationSubmitted); se espera el resultado. */
    PHONE_SUBMITTED,
    /** Telefono rechazado: el proceso sigue vigente hasta que venza y se puede reenviar. */
    PHONE_REJECTED,
    /** Reserva confirmada. Solo puede cancelarse. */
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    INVALID,
    FAILED;

    private static final Map<ReservationStatus, Set<ReservationStatus>> TRANSITIONS =
            new EnumMap<>(ReservationStatus.class);

    static {
        TRANSITIONS.put(STARTED, EnumSet.of(HELD, FAILED));
        // El evento de la catedra puede llegar antes de que se registre la respuesta REST: HELD -> PHONE_REQUESTED
        TRANSITIONS.put(HELD, EnumSet.of(WAITING_FOR_PHONE, PHONE_REQUESTED, EXPIRED, INVALID, FAILED));
        // Hacia CONFIRMED desde estados previos: recuperacion cuando se perdio un evento y la consulta REST la muestra confirmada
        TRANSITIONS.put(WAITING_FOR_PHONE, EnumSet.of(PHONE_REQUESTED, CONFIRMED, EXPIRED, INVALID, FAILED));
        TRANSITIONS.put(PHONE_REQUESTED, EnumSet.of(PHONE_SUBMITTED, CONFIRMED, EXPIRED, INVALID, FAILED));
        TRANSITIONS.put(PHONE_SUBMITTED, EnumSet.of(PHONE_REJECTED, CONFIRMED, EXPIRED, INVALID, FAILED));
        TRANSITIONS.put(PHONE_REJECTED, EnumSet.of(PHONE_SUBMITTED, EXPIRED, INVALID, FAILED));
        TRANSITIONS.put(CONFIRMED, EnumSet.of(CANCELLED));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(ReservationStatus.class));
        TRANSITIONS.put(EXPIRED, EnumSet.noneOf(ReservationStatus.class));
        TRANSITIONS.put(INVALID, EnumSet.noneOf(ReservationStatus.class));
        TRANSITIONS.put(FAILED, EnumSet.noneOf(ReservationStatus.class));
    }

    public boolean canTransitionTo(ReservationStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public Set<ReservationStatus> allowedTransitions() {
        return Collections.unmodifiableSet(TRANSITIONS.get(this));
    }

    /** Un estado final no tiene transiciones: un evento tardio no puede reabrirlo. */
    public boolean isFinal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}