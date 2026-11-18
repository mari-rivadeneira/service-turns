package com.example.hexagonal.turns.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/** Fabrica tokens como los emite service-catalog (login, userId, auth, iat y exp), solo para los tests. */
public class JwtTestTokens {

    private final JwtEncoder encoder;

    public JwtTestTokens(SecretKey key) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /** Clave de 64 bytes con un relleno fijo: sirve para fabricar una clave distinta a la real. */
    public static SecretKey keyOf(int fill) {
        byte[] bytes = new byte[64];
        Arrays.fill(bytes, (byte) fill);
        return new SecretKeySpec(bytes, "HmacSHA512");
    }

    public String valid(long userId, String login, String authorities) {
        Instant now = Instant.now();
        return build(userId, login, authorities, MacAlgorithm.HS512, now, now.plus(1, ChronoUnit.HOURS));
    }

    public String expired(long userId, String login, String authorities) {
        Instant now = Instant.now();
        return build(userId, login, authorities, MacAlgorithm.HS512,
                now.minus(3, ChronoUnit.HOURS), now.minus(2, ChronoUnit.HOURS));
    }

    public String withoutUserId(String login, String authorities) {
        Instant now = Instant.now();
        return build(null, login, authorities, MacAlgorithm.HS512, now, now.plus(1, ChronoUnit.HOURS));
    }

    public String withAlgorithm(MacAlgorithm algorithm, long userId, String login, String authorities) {
        Instant now = Instant.now();
        return build(userId, login, authorities, algorithm, now, now.plus(1, ChronoUnit.HOURS));
    }

    private String build(Long userId, String login, String authorities, MacAlgorithm algorithm,
                         Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(login)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(JwtClaims.AUTH, authorities);
        if (userId != null) {
            claims.claim(JwtClaims.USER_ID, userId);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), claims.build()))
                .getTokenValue();
    }
}