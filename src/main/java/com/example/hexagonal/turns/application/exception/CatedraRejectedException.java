package com.example.hexagonal.turns.application.exception;

/** La catedra rechazo la operacion con un 4xx: es una respuesta firme y la operacion no tuvo efecto. */
public class CatedraRejectedException extends TurnsException {

    private final int status;
    private final String code;

    public CatedraRejectedException(int status, String code, String detail) {
        super("La catedra rechazo la operacion con estado " + status
                + (code != null ? " (" + code + ")" : "")
                + (detail != null ? ": " + detail : ""));
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }

    /** Codigo funcional estable; puede ser null (los 401 y 403 de Spring Security no lo incluyen). */
    public String getCode() { return code; }

    public boolean hasCode(String expected) { return expected.equals(code); }

    /** El JWT tecnico es invalido o vencio: es un problema de configuracion, no del usuario. */
    public boolean isAuthenticationFailure() { return status == 401; }
}