package com.example.hexagonal.turns.application.exception;

/** Codigos funcionales de error del contrato de la catedra (version 1). */
public final class CatedraErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String PROFESSIONAL_NOT_FOUND = "PROFESSIONAL_NOT_FOUND";
    public static final String PROFESSIONAL_DISABLED = "PROFESSIONAL_DISABLED";
    public static final String INVALID_SLOT = "INVALID_SLOT";
    public static final String SLOT_ALREADY_RESERVED = "SLOT_ALREADY_RESERVED";
    public static final String SLOT_ALREADY_HELD = "SLOT_ALREADY_HELD";
    public static final String HOLD_NOT_FOUND = "HOLD_NOT_FOUND";
    public static final String HOLD_EXPIRED = "HOLD_EXPIRED";
    public static final String HOLD_OWNER_MISMATCH = "HOLD_OWNER_MISMATCH";
    public static final String EXTERNAL_PATIENT_MISMATCH = "EXTERNAL_PATIENT_MISMATCH";
    public static final String RESERVATION_PROCESS_ALREADY_CONFIRMED = "RESERVATION_PROCESS_ALREADY_CONFIRMED";
    public static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
    public static final String RESERVATION_OWNER_MISMATCH = "RESERVATION_OWNER_MISMATCH";
    public static final String RESERVATION_NOT_CANCELLABLE = "RESERVATION_NOT_CANCELLABLE";
    public static final String INVALID_DATE_RANGE = "INVALID_DATE_RANGE";
    public static final String STUDENT_INTEGRATION_NOT_FOUND = "STUDENT_INTEGRATION_NOT_FOUND";

    private CatedraErrorCodes() {
    }
}
