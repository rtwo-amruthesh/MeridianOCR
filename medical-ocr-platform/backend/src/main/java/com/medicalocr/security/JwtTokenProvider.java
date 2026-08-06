package com.medicalocr.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final String INSECURE_DEFAULT =
            "medical-ocr-secret-key-change-in-production-minimum-256-bits-required-for-security";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration}")
    @Getter
    private long jwtExpiration;

    private SecretKey key;

    /**
     * Refuse to start on the committed placeholder. A signing key in version
     * control lets anyone with the repository mint a token for any account, and
     * a warning in a log is too easy to miss.
     */
    @PostConstruct
    void init() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set.");
        }
        if (INSECURE_DEFAULT.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "JWT_SECRET is still the placeholder from the sample config. "
                    + "Generate one with: openssl rand -base64 48");
        }
        byte[] bytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes for HS256.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String generateToken(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + jwtExpiration))
                .signWith(key)
                .compact();
    }

    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return claims.getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected token: {}", ex.getMessage());
            return false;
        }
    }
}
