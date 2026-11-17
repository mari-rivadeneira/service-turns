package com.example.hexagonal.turns.application.exception;

/**
 * No se pudo completar la llamada a la catedra (red, timeout, 5xx o respuesta invalida).
 * outcomeUnknown: la operacion tiene efectos y el pedido pudo haber llegado, asi que pudo ejecutarse. En ese caso hay
 * que consultar o reconciliar el estado antes de repetirla. Es false cuando el pedido seguro no salio (conexion
 * rechazada o connect timeout) o cuando la operacion es solo de lectura.
 */
public class CatedraUnavailableException extends TurnsException {

    private final boolean outcomeUnknown;

    public CatedraUnavailableException(String message, boolean outcomeUnknown, Throwable cause) {
        super(message, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() { return outcomeUnknown; }
}
