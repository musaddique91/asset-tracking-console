package com.zonewatch.ops;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private final JdbcClient jdbc;

    public HealthService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Ready when the database answers. */
    public boolean ready() {
        try {
            return jdbc.sql("SELECT 1").query(Integer.class).single() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
