package com.example.hexagonal.turns.infrastructure.web.controller;

import com.example.hexagonal.turns.domain.port.in.GetAvailabilityUseCase;
import com.example.hexagonal.turns.domain.port.in.GetAvailabilityUseCase.AvailabilityQuery;
import com.example.hexagonal.turns.infrastructure.security.AuthenticatedUser;
import com.example.hexagonal.turns.infrastructure.web.dto.AvailabilityResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
public class AvailabilityController {

    private final GetAvailabilityUseCase getAvailabilityUseCase;

    public AvailabilityController(GetAvailabilityUseCase getAvailabilityUseCase) {
        this.getAvailabilityUseCase = getAvailabilityUseCase;
    }

    /** Turnos disponibles de un profesional en una fecha (yyyy-MM-dd). El token del usuario se reenvia al catalogo. */
    @GetMapping("/availability")
    public AvailabilityResponse getAvailability(
            @RequestParam("professionalId") long professionalId,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        return AvailabilityResponse.from(
                getAvailabilityUseCase.getAvailability(new AvailabilityQuery(professionalId, date, user.token())));
    }
}
