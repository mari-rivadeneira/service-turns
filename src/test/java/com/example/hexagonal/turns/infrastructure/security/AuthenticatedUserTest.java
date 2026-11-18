package com.example.hexagonal.turns.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatedUserTest {

    private Jwt.Builder jwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token-secreto")
                .header("alg", "HS512")
                .subject("ana")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600));
    }

    @Test
    @DisplayName("Obtiene el id, el login y las autoridades del token")
    void shouldReadIdentityFromToken() {
        AuthenticatedUser user = AuthenticatedUser.from(
                jwt().claim("userId", 42L).claim("auth", "ROLE_USER ROLE_ADMIN").build());

        assertEquals(42L, user.userId());
        assertEquals("ana", user.login());
        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), user.authorities());
        assertTrue(user.isAdmin());
        assertEquals("token-secreto", user.token());
    }

    @Test
    @DisplayName("El id puede llegar como entero o como long")
    void shouldAcceptAnyNumericType() {
        assertEquals(7L, AuthenticatedUser.from(jwt().claim("userId", 7).build()).userId());
    }

    @Test
    @DisplayName("Un token valido que no trae userId no identifica a nadie")
    void shouldRejectTokenWithoutUserId() {
        assertThrows(BadCredentialsException.class, () -> AuthenticatedUser.from(jwt().build()));
    }

    @Test
    @DisplayName("Sin autoridades, o con el claim en blanco, no es administrador")
    void shouldHandleMissingAuthorities() {
        AuthenticatedUser withoutClaim = AuthenticatedUser.from(jwt().claim("userId", 1L).build());
        AuthenticatedUser blankClaim = AuthenticatedUser.from(jwt().claim("userId", 1L).claim("auth", "  ").build());

        assertTrue(withoutClaim.authorities().isEmpty());
        assertTrue(blankClaim.authorities().isEmpty());
        assertFalse(withoutClaim.isAdmin());
        assertFalse(AuthenticatedUser.from(jwt().claim("userId", 1L).claim("auth", "ROLE_USER").build()).isAdmin());
    }

    @Test
    @DisplayName("toString no incluye el token, para que no se filtre a los logs")
    void shouldNotExposeTheTokenInToString() {
        AuthenticatedUser user = AuthenticatedUser.from(jwt().claim("userId", 42L).build());

        assertFalse(user.toString().contains("token-secreto"));
        assertTrue(user.toString().contains("42"));
    }
}
