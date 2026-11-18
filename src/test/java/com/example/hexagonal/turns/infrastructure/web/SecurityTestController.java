package com.example.hexagonal.turns.infrastructure.web;

import com.example.hexagonal.turns.application.exception.CatalogRejectedException;
import com.example.hexagonal.turns.application.exception.CatalogUnavailableException;
import com.example.hexagonal.turns.application.exception.CatedraRejectedException;
import com.example.hexagonal.turns.application.exception.CatedraUnavailableException;
import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.exception.InvalidStateTransitionException;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import com.example.hexagonal.turns.infrastructure.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Solo existe en los tests: permite probar identidad, roles, propiedad y traduccion de errores sin endpoints reales. */
@RestController
@RequestMapping("/api/test")
class SecurityTestController {

    private final ReservationProcessRepository processRepository;

    SecurityTestController(ReservationProcessRepository processRepository) {
        this.processRepository = processRepository;
    }

    @GetMapping("/whoami")
    Map<String, Object> whoami(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.userId());
        body.put("login", user.login());
        body.put("authorities", new TreeSet<>(user.authorities()));
        return body;
    }

    @GetMapping("/admin-only")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    String adminOnly() {
        return "ok";
    }

    /** Igual que lo haran los endpoints reales: busca por id Y por el usuario del token. */
    @GetMapping("/processes/{id}")
    Map<String, Object> process(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        ReservationProcess process = processRepository.findByIdAndUserId(id, user.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", process.getId().toString());
        body.put("userId", process.getUserId());
        body.put("status", process.getStatus().name());
        return body;
    }

    @GetMapping("/errors/{kind}")
    String errors(@PathVariable("kind") String kind) {
        switch (kind) {
            case "state" -> throw new InvalidStateTransitionException(ReservationStatus.HELD, ReservationStatus.STARTED);
            case "concurrent" -> throw new ConcurrentUpdateException(UUID.randomUUID(), null);
            case "catalog-down" -> throw new CatalogUnavailableException("catalogo caido", null);
            case "catedra-down" -> throw new CatedraUnavailableException("catedra caida", true, null);
            case "catalog-rejected" -> throw new CatalogRejectedException(401, "UNAUTHORIZED", "detalle interno");
            case "catedra-rejected" -> throw new CatedraRejectedException(409, "SLOT_ALREADY_HELD", "detalle interno");
            default -> throw new IllegalStateException("secreto-interno");
        }
    }
}