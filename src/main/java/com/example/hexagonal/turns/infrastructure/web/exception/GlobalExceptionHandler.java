package com.example.hexagonal.turns.infrastructure.web.exception;

import com.example.hexagonal.turns.application.exception.CatalogRejectedException;
import com.example.hexagonal.turns.application.exception.CatalogUnavailableException;
import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;
import com.example.hexagonal.turns.infrastructure.web.ProblemBody;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Set;

/**
 * Traduce las excepciones a application/problem+json con un code estable. Las issues siguientes agregan aca los
 * errores propios de cada caso de uso (reserva no encontrada, turno ocupado, etc.).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStateTransition(InvalidStateTransitionException ex,
                                                                            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "La operacion no es valida para el estado actual de la reserva", request);
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrentUpdate(ConcurrentUpdateException ex,
                                                                      HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                "La reserva fue modificada por otra operacion: vuelva a intentarlo", request);
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleCatalogUnavailable(CatalogUnavailableException ex,
                                                                        HttpServletRequest request) {
        log.warn("service-catalog no disponible en {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE",
                "service-catalog no esta disponible: no se puede validar la informacion vigente", request);
    }

    @ExceptionHandler(CatedraUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleCatedraUnavailable(CatedraUnavailableException ex,
                                                                        HttpServletRequest request) {
        log.warn("Catedra no disponible en {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "CATEDRA_UNAVAILABLE",
                "El servicio de la catedra no esta disponible", request);
    }

    @ExceptionHandler(CatalogRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleCatalogRejected(CatalogRejectedException ex,
                                                                     HttpServletRequest request) {
        log.warn("service-catalog rechazo un pedido de {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.BAD_GATEWAY, "CATALOG_ERROR", "service-catalog rechazo la consulta", request);
    }

    /** Rechazos de la catedra que ningun caso de uso tradujo: se informan sin exponer el detalle. */
    @ExceptionHandler(CatedraRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleCatedraRejected(CatedraRejectedException ex,
                                                                     HttpServletRequest request) {
        log.warn("La catedra rechazo un pedido de {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.BAD_GATEWAY, "CATEDRA_ERROR", "La catedra rechazo la operacion", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                    HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "El cuerpo de la peticion no es un JSON valido", request);
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(TypeMismatchException ex,
                                                                  HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "El valor de un parametro no tiene el formato esperado", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                        HttpServletRequest request) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(new HttpMethod[0]));
        }
        return builder.body(ProblemBody.of(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "El metodo HTTP no esta permitido para este recurso", request.getRequestURI()));
    }

    // Se lanzan dentro de un controller (seguridad por metodo o identidad invalida): sin estos handlers darian 500
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex,
                                                                  HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "No tiene permisos para realizar esta operacion", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex,
                                                                    HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Se requiere autenticacion valida para acceder a este recurso", request);
    }

    /** Errores estandar de Spring (ruta inexistente, tipo de contenido, etc.) con su estado; el resto es un 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            return respond(status, codeFor(status.value()), detailFor(status.value()), request);
        }
        log.error("Error inesperado en {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Ocurrio un error inesperado", request);
    }

    private ResponseEntity<Map<String, Object>> respond(HttpStatusCode status, String code, String detail,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.of(status, code, detail, request.getRequestURI()));
    }

    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "ERROR";
        };
    }

    private static String detailFor(int status) {
        return switch (status) {
            case 400 -> "La peticion es invalida";
            case 404 -> "El recurso solicitado no existe";
            case 405 -> "El metodo HTTP no esta permitido para este recurso";
            case 406 -> "El formato de respuesta solicitado no esta disponible";
            case 415 -> "El tipo de contenido no esta soportado";
            default -> status >= 500 ? "Ocurrio un error inesperado" : "No se pudo procesar la peticion";
        };
    }
}