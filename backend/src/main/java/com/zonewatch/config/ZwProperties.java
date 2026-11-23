package com.zonewatch.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deploy-time settings. Every value comes from the environment (see application.yml);
 * nothing here is a secret baked into the image.
 *
 * @param instance          name of this instance in logs and metrics
 * @param usersFile         path of the mounted users file (deploy-time secret)
 * @param zonesFile         optional override of the bundled zones.geojson
 * @param jwtSecret         optional base64 signing key; when empty a key is generated once and kept in the database
 * @param jwtTtl            token lifetime
 * @param maxFutureSkew     observedAt further than this in the future is rejected as INVALID_TIMESTAMP
 * @param streamPollInterval how often an instance looks for changes to push to its live clients
 * @param mode              serve (default), migrate or backfill-source-type
 */
@ConfigurationProperties(prefix = "zw")
public record ZwProperties(
        String instance,
        String usersFile,
        String zonesFile,
        String jwtSecret,
        Duration jwtTtl,
        Duration maxFutureSkew,
        Duration streamPollInterval,
        String mode) {

    public boolean serving() {
        return mode == null || mode.isBlank() || "serve".equals(mode);
    }
}
