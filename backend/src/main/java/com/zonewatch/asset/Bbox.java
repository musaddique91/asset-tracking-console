package com.zonewatch.asset;

import com.zonewatch.common.ApiException;

/**
 * minLon,minLat,maxLon,maxLat. When minLon is greater than maxLon the box crosses the 180 degree
 * meridian (RFC 7946, 5.2) and is the union of [minLon, 180] and [-180, maxLon].
 */
public record Bbox(double minLon, double minLat, double maxLon, double maxLat) {

    public boolean crossesAntimeridian() {
        return minLon > maxLon;
    }

    public static Bbox parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.validation("bbox is required: minLon,minLat,maxLon,maxLat");
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != 4) {
            throw ApiException.validation("bbox must have four numbers: minLon,minLat,maxLon,maxLat");
        }
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                v[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw ApiException.validation("bbox must have four numbers: minLon,minLat,maxLon,maxLat");
            }
            if (!Double.isFinite(v[i])) {
                throw ApiException.validation("bbox values must be finite numbers");
            }
        }
        if (v[0] < -180 || v[0] > 180 || v[2] < -180 || v[2] > 180) {
            throw ApiException.validation("bbox longitudes must be from -180 to 180");
        }
        if (v[1] < -90 || v[1] > 90 || v[3] < -90 || v[3] > 90) {
            throw ApiException.validation("bbox latitudes must be from -90 to 90");
        }
        if (v[1] > v[3]) {
            throw ApiException.validation("bbox minLat must not be greater than maxLat");
        }
        return new Bbox(v[0], v[1], v[2], v[3]);
    }
}
