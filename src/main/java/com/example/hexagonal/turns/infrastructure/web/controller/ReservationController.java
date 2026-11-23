package com.example.hexagonal.turns.infrastructure.web.controller;

import com.example.hexagonal.turns.domain.port.in.ConfirmReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.GetReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase.StartReservationCommand;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase.StartReservationResult;
import com.example.hexagonal.turns.domain.port.in.SubmitPhoneUseCase;
import com.example.hexagonal.turns.infrastructure.security.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final StartReservationUseCase startReservationUseCase;
    private final ConfirmReservationUseCase confirmReservationUseCase;
    private final SubmitPhoneUseCase submitPhoneUseCase;
    private final GetReservationUseCase getReservationUseCase;

    public ReservationController(StartReservationUseCase startReservationUseCase,
                                 ConfirmReservationUseCase confirmReservationUseCase,
                                 SubmitPhoneUseCase submitPhoneUseCase,
                                 GetReservationUseCase getReservationUseCase) {
        this.startReservationUseCase = startReservationUseCase;
        this.confirmReservationUseCase = confirmReservationUseCase;
        this.submitPhoneUseCase = submitPhoneUseCase;
        this.getReservationUseCase = getReservationUseCase;
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

    /** Estado de una reserva propia. La de otro usuario responde 404. */
    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return ReservationResponse.from(getReservationUseCase.get(user.userId(), id));
    }
}