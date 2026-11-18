package com.example.hexagonal.turns.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/** service-turns solo valida tokens: los emite service-catalog con este mismo secreto. */
@Configuration
public class JwtConfiguration {

    /** HS512 exige una clave de al menos 512 bits. */
    private static final int MIN_SECRET_BYTES = 64;

    @Bean
    public SecretKey jwtSecretKey(@Value("${jwt.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Falta la variable JWT_SECRET: tiene que ser la misma que usa service-catalog");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET no es un Base64 valido", e);
        }

        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET es demasiado corta: HS512 requiere al menos "
                    + MIN_SECRET_BYTES + " bytes (usa openssl rand -base64 64)");
        }
        return new SecretKeySpec(bytes, "HmacSHA512");
    }

    /** Solo acepta HS512: un token firmado con otro algoritmo (HS256, none) se rechaza. */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS512).build();
    }
}