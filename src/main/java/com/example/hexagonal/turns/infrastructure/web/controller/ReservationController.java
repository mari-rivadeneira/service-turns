package com.example.hexagonal.turns.infrastructure.web.controller;

import com.example.hexagonal.turns.domain.port.in.ConfirmReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase.StartReservationCommand;
import com.example.hexagonal.turns.domain.port.in.StartReservationUseCase.StartReservationResult;
import com.example.hexagonal.turns.infrastructure.security.AuthenticatedUser;
import com.example.hexagonal.turns.infrastructure.web.dto.ReservationResponse;
import com.example.hexagonal.turns.infrastructure.web.dto.StartReservationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

    public ReservationController(StartReservationUseCase startReservationUseCase,
                                 ConfirmReservationUseCase confirmReservationUseCase) {
        this.startReservationUseCase = startReservationUseCase;
        this.confirmReservationUseCase = confirmReservationUseCase;
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
}