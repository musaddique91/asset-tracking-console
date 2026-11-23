package com.zonewatch.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonewatch.config.ZwProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.UncheckedIOException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds users from the file mounted at deploy time (ZW_USERS_FILE). Passwords are stored as BCrypt
 * hashes and never logged. Safe to run on both instances at once (upsert).
 */
@Component
public class UserSeeder implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final ZwProperties props;
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;

    public UserSeeder(ZwProperties props, JdbcClient jdbc, PasswordEncoder encoder, ObjectMapper mapper) {
        this.props = props;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mapper = mapper;
    }

    /** Runs before the web server accepts requests, so a login never races the seeding. */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            seed();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the users file", e);
        }
    }

    void seed() throws IOException {
        if (!props.serving()) {
            return;
        }
        if (props.usersFile() == null || props.usersFile().isBlank()) {
            throw new IllegalStateException("ZW_USERS_FILE is not set: mount the users file and point ZW_USERS_FILE at it");
        }
        Path path = Path.of(props.usersFile());
        JsonNode root = mapper.readTree(Files.readAllBytes(path));
        if (!root.isArray()) {
            throw new IllegalStateException("users file must be a JSON array");
        }
        int created = 0;
        int updated = 0;
        for (JsonNode entry : root) {
            String username = text(entry, "username");
            String password = text(entry, "password");
            Role role = Role.valueOf(text(entry, "role"));
            String existing = jdbc.sql("SELECT password_hash FROM app_user WHERE username = :u")
                    .param("u", username).query(String.class).optional().orElse(null);
            if (existing != null && encoder.matches(password, existing)) {
                jdbc.sql("UPDATE app_user SET role = :r WHERE username = :u AND role <> :r")
                        .param("r", role.name()).param("u", username).update();
                continue;
            }
            jdbc.sql("""
                    INSERT INTO app_user (username, password_hash, role) VALUES (:u, :h, :r)
                    ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                        role = EXCLUDED.role, updated_at = now()
                    """)
                    .param("u", username).param("h", encoder.encode(password)).param("r", role.name()).update();
            if (existing == null) {
                created++;
            } else {
                updated++;
            }
        }
        log.info("users file applied: {} entries, {} created, {} updated", root.size(), created, updated);
    }

    private static String text(JsonNode entry, String field) {
        JsonNode node = entry.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalStateException("users file entry is missing '" + field + "'");
        }
        return node.asText();
    }
}
