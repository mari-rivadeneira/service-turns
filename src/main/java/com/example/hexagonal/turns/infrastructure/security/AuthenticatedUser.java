package com.example.hexagonal.turns.infrastructure.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.Set;

/**
 * Usuario autenticado de un pedido. La identidad sale siempre del token, nunca del cuerpo, la URL ni un parametro.
 * Guarda el token para reenviarlo a service-catalog; por eso toString no lo incluye.
 */
public record AuthenticatedUser(long userId, String login, Set<String> authorities, String token) {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** @throws BadCredentialsException si el token es valido pero no identifica a un usuario (sin userId) */
    public static AuthenticatedUser from(Jwt jwt) {
        Number userId = jwt.getClaim(JwtClaims.USER_ID);
        if (userId == null) {
            throw new BadCredentialsException("El token no identifica a un usuario");
        }
        String auth = jwt.getClaimAsString(JwtClaims.AUTH);
        Set<String> authorities = auth == null || auth.isBlank()
                ? Set.of()
                : Set.copyOf(Arrays.asList(auth.trim().split("\\s+")));
        return new AuthenticatedUser(userId.longValue(), jwt.getSubject(), authorities, jwt.getTokenValue());
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }

    public boolean isAdmin() {
        return hasAuthority(ROLE_ADMIN);
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[userId=" + userId + ", login=" + login + "]";
    }
}
