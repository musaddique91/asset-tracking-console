package com.zonewatch.auth;

import com.zonewatch.common.ApiException;
import com.zonewatch.common.ProblemCode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final String dummyHash;

    public AuthService(JdbcClient jdbc, PasswordEncoder encoder, TokenService tokens) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.tokens = tokens;
        // compared against for unknown users, so that response time does not reveal which usernames exist
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public record LoginResult(String accessToken, String tokenType, long expiresInSec, Map<String, String> user) {
    }

    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "username and password are required");
        }
        var row = jdbc.sql("SELECT password_hash, role FROM app_user WHERE username = :u")
                .param("u", username)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)})
                .optional();
        boolean ok = encoder.matches(password, row.map(r -> r[0]).orElse(dummyHash)) && row.isPresent();
        if (!ok) {
            log.info("login refused");
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "invalid username or password");
        }
        Role role = Role.valueOf(row.get()[1]);
        log.info("login accepted role={}", role);
        return new LoginResult(tokens.issue(username, role), "Bearer", tokens.ttlSeconds(),
                Map.of("username", username, "role", role.name()));
    }
}
