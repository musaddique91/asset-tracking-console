package com.zonewatch.auth;

public enum Role {
    INGEST, OPERATOR, SUPERVISOR;

    public String authority() {
        return "ROLE_" + name();
    }
}
