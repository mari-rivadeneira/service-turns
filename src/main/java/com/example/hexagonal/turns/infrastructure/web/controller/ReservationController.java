package com.example.hexagonal.turns.infrastructure.web.controller;

import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.CancelReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.ConfirmReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.GetReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.ListReservationsUseCase;
import com.example.hexagonal.turns.domain.port.in.ReconcileReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase.StartReservationCommand;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase.StartReservationResult;
import com.example.hexagonal.turns.domain.port.in.SubmitPhoneUseCase;
import com.example.hexagonal.turns.infrastructure.security.AuthenticatedUser;
import com.example.hexagonal.turns.infrastructure.web.dto.CancelReservationRequest;
import com.example.hexagonal.turns.infrastructure.web.dto.ReservationResponse;
import com.example.hexagonal.turns.infrastructure.web.dto.StartReservationRequest;
import com.example.hexagonal.turns.infrastructure.web.dto.SubmitPhoneRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final StartReservationUseCase startReservationUseCase;
    private final ConfirmReservationUseCase confirmReservationUseCase;
    private final SubmitPhoneUseCase submitPhoneUseCase;
    private final GetReservationUseCase getReservationUseCase;
    private final ListReservationsUseCase listReservationsUseCase;
    private final CancelReservationUseCase cancelReservationUseCase;
    private final ReconcileReservationUseCase reconcileReservationUseCase;

    public ReservationController(StartReservationUseCase startReservationUseCase,
                                 ConfirmReservationUseCase confirmReservationUseCase,
                                 SubmitPhoneUseCase submitPhoneUseCase,
                                 GetReservationUseCase getReservationUseCase,
                                 ListReservationsUseCase listReservationsUseCase,
                                 CancelReservationUseCase cancelReservationUseCase,
                                 ReconcileReservationUseCase reconcileReservationUseCase) {
        this.startReservationUseCase = startReservationUseCase;
        this.confirmReservationUseCase = confirmReservationUseCase;
        this.submitPhoneUseCase = submitPhoneUseCase;
        this.getReservationUseCase = getReservationUseCase;
        this.listReservationsUseCase = listReservationsUseCase;
        this.cancelReservationUseCase = cancelReservationUseCase;
        this.reconcileReservationUseCase = reconcileReservationUseCase;
    }

    /** 201 si se creo el hold; 200 si el pedido repetido encontro la reserva ya iniciada. */
    @PostMapping
    public ResponseEntity<ReservationResponse> start(@RequestBody StartReservationRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        StartReservationResult result = startReservationUseCase.start(new StartReservationCommand(
                user.userId(), user.token(), request.professionalId(), request.date(), request.startTime(),
                request.patientFirstName(), request.patientLastName()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ReservationResponse.from(result.process()));
    }

    /** Mis reservas, de la mas nueva a la mas vieja (hasta 100), opcionalmente de un estado. */
    @GetMapping
    public List<ReservationResponse> list(@RequestParam(name = "status", required = false) ReservationStatus status,
                                          @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return listReservationsUseCase.list(user.userId(), status).stream().map(ReservationResponse::from).toList();
    }

    /** Estado de una reserva propia. La de otro usuario responde 404. */
    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return ReservationResponse.from(getReservationUseCase.get(user.userId(), id));
    }

    /** 202: la confirmacion inicial fue aceptada; falta el telefono, que la catedra pide por Kafka. */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReservationResponse> confirm(@PathVariable("id") UUID id,
                                                       @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return ResponseEntity.accepted()
                .body(ReservationResponse.from(confirmReservationUseCase.confirm(user.userId(), id)));
    }

    /** 202: el telefono se publico; el resultado llega por Kafka y se consulta con GET. */
    @PostMapping("/{id}/phone")
    public ResponseEntity<ReservationResponse> submitPhone(@PathVariable("id") UUID id,
                                                           @RequestBody SubmitPhoneRequest request,
                                                           @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return ResponseEntity.accepted()
                .body(ReservationResponse.from(submitPhoneUseCase.submit(user.userId(), id, request.phoneNumber())));
    }

    /** Cancela una reserva confirmada propia. El cuerpo, con el motivo, es opcional. Es idempotente. */
    @PostMapping("/{id}/cancel")
    public ReservationResponse cancel(@PathVariable("id") UUID id,
                                      @RequestBody(required = false) CancelReservationRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        String reason = request == null ? null : request.reason();
        return ReservationResponse.from(cancelReservationUseCase.cancel(user.userId(), id, reason));
    }

    /** Consulta a la catedra el estado real de la reserva y converge: sirve cuando se perdio un evento. */
    @PostMapping("/{id}/refresh")
    public ReservationResponse refresh(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return ReservationResponse.from(reconcileReservationUseCase.reconcile(user.userId(), id));
    }
}