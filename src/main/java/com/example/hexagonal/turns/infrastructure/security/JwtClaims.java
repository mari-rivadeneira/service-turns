package com.example.hexagonal.turns.infrastructure.security;

public final class JwtClaims {

    /** Autoridades separadas por espacio (mismo formato que emite service-catalog). */
    public static final String AUTH = "auth";

    /** Id numerico del usuario: de aca sale la identidad en cada pedido. */
    public static final String USER_ID = "userId";

    private JwtClaims() {
    }
}
