package com.example.hexagonal.turns.infrastructure.client.support;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Locale;

public final class NetworkErrors {

    private NetworkErrors() {
    }

    /**
     * true si el error indica que el pedido seguro no llego al servidor (conexion rechazada, host desconocido, sin ruta o
     * timeout al conectar). Ante la duda devuelve false: un timeout de lectura no prueba que la operacion haya fallado.
     */
    public static boolean requestWasNotDelivered(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConnectException || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException || current instanceof HttpConnectTimeoutException) {
                return true;
            }
            if (current instanceof SocketTimeoutException && current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.ROOT).contains("connect")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}