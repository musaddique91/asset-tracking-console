package com.zonewatch.auth;

import com.zonewatch.config.ZwProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final SigningKeyProvider keys;
    private final Duration ttl;

    public TokenService(SigningKeyProvider keys, ZwProperties props) {
        this.keys = keys;
        this.ttl = props.jwtTtl() == null ? Duration.ofHours(12) : props.jwtTtl();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    public String issue(String username, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(keys.key())
                .compact();
    }

    /** The caller of a valid, unexpired token; empty for anything else. */
    public Optional<CurrentUser> verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(keys.key()).build().parseSignedClaims(token).getPayload();
            return Optional.of(new CurrentUser(claims.getSubject(), Role.valueOf(claims.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
