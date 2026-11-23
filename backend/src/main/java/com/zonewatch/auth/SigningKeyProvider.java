package com.zonewatch.auth;

import com.zonewatch.config.ZwProperties;
import io.jsonwebtoken.security.Keys;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The token signing key must be the same on both instances. When ZW_JWT_SECRET is given it is used;
 * otherwise the first instance to start generates a random key and stores it in the database, and
 * every other instance reads that one. No key is ever in the image, the repository or a log.
 */
@Component
public class SigningKeyProvider {

    private static final String NAME = "jwt-signing-key";

    private final ZwProperties props;
    private final JdbcClient jdbc;
    private volatile SecretKey key;

    public SigningKeyProvider(ZwProperties props, JdbcClient jdbc) {
        this.props = props;
        this.jdbc = jdbc;
    }

    public SecretKey key() {
        SecretKey k = key;
        if (k == null) {
            synchronized (this) {
                if (key == null) {
                    key = load();
                }
                k = key;
            }
        }
        return k;
    }

    private SecretKey load() {
        String configured = props.jwtSecret();
        if (configured != null && !configured.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(configured.trim());
            if (bytes.length < 32) {
                throw new IllegalStateException("ZW_JWT_SECRET must be at least 256 bits (32 bytes, base64)");
            }
            return Keys.hmacShaKeyFor(bytes);
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        jdbc.sql("INSERT INTO app_secret (name, value) VALUES (:n, :v) ON CONFLICT (name) DO NOTHING")
                .param("n", NAME).param("v", Base64.getEncoder().encodeToString(fresh)).update();
        String stored = jdbc.sql("SELECT value FROM app_secret WHERE name = :n").param("n", NAME)
                .query(String.class).single();
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(stored));
    }
}
