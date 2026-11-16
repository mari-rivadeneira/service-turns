package com.example.hexagonal.turns.application.exception;

/** service-catalog rechazo el pedido con un 4xx distinto de "no encontrado" (por ejemplo, token invalido). */
public class CatalogRejectedException extends TurnsException {

    private final int status;
    private final String code;

    public CatalogRejectedException(int status, String code, String detail) {
        super("service-catalog rechazo el pedido con estado " + status
                + (code != null ? " (" + code + ")" : "")
                + (detail != null ? ": " + detail : ""));
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }

    public String getCode() { return code; }
}