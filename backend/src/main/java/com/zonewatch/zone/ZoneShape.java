package com.zonewatch.zone;

import java.util.ArrayList;
import java.util.List;

/**
 * Planar point-in-polygon on WGS84 longitude/latitude, as GeoJSON defines it (RFC 7946):
 * a point is inside when it is inside an outer ring and not inside one of that polygon's holes.
 * A point exactly on a boundary counts as inside the zone. Several polygons (MultiPolygon) are OR-ed.
 *
 * Rings that cross the 180 degree meridian (consecutive longitudes more than 180 apart, as in a
 * box written 179.3 .. -179.4) are unwrapped to a continuous longitude range above 180, and
 * points are tested at both lon and lon + 360. Without this such a ring would cover almost the
 * whole globe.
 */
public final class ZoneShape {

    private static final double EPS = 1e-12;

    /** One polygon: ring 0 is the outer boundary, the others are holes. Each ring is {lon[], lat[]}. */
    record Polygon(List<double[][]> rings) {
    }

    private final List<Polygon> polygons;
    private final double minLon;
    private final double maxLon;
    private final double minLat;
    private final double maxLat;
    private final boolean crossesAntimeridian;

    ZoneShape(List<Polygon> polygons, boolean crossesAntimeridian) {
        this.polygons = polygons;
        this.crossesAntimeridian = crossesAntimeridian;
        double a = Double.MAX_VALUE, b = -Double.MAX_VALUE, c = Double.MAX_VALUE, d = -Double.MAX_VALUE;
        for (Polygon p : polygons) {
            double[][] outer = p.rings().get(0);
            for (int i = 0; i < outer[0].length; i++) {
                a = Math.min(a, outer[0][i]);
                b = Math.max(b, outer[0][i]);
                c = Math.min(c, outer[1][i]);
                d = Math.max(d, outer[1][i]);
            }
        }
        this.minLon = a;
        this.maxLon = b;
        this.minLat = c;
        this.maxLat = d;
    }

    /**
     * Builds a shape from GeoJSON coordinates. polygons is a list of polygons, each a list of rings,
     * each a list of [lon, lat] positions. Unclosed rings are closed.
     */
    public static ZoneShape of(List<List<List<double[]>>> polygons) {
        List<Polygon> result = new ArrayList<>();
        boolean crosses = false;
        for (List<List<double[]>> polygon : polygons) {
            List<double[][]> rings = new ArrayList<>();
            for (List<double[]> ring : polygon) {
                double[][] r = toArrays(ring);
                if (crossesMeridian(r[0])) {
                    crosses = true;
                    unwrap(r[0]);
                }
                rings.add(r);
            }
            result.add(new Polygon(rings));
        }
        return new ZoneShape(result, crosses);
    }

    public boolean contains(double lon, double lat) {
        if (lat < minLat - EPS || lat > maxLat + EPS) {
            return false;
        }
        if (containsPlanar(lon, lat)) {
            return true;
        }
        return crossesAntimeridian && lon < 0 && containsPlanar(lon + 360.0, lat);
    }

    /** Bounding box in the shape's own (possibly unwrapped, above 180) longitude range. */
    public double[] bounds() {
        return new double[] {minLon, minLat, maxLon, maxLat};
    }

    public boolean crossesAntimeridian() {
        return crossesAntimeridian;
    }

    private boolean containsPlanar(double lon, double lat) {
        if (lon < minLon - EPS || lon > maxLon + EPS) {
            return false;
        }
        for (Polygon p : polygons) {
            int outer = locate(p.rings().get(0), lon, lat);
            if (outer == OUTSIDE) {
                continue;
            }
            if (outer == ON_EDGE) {
                return true;
            }
            boolean inHole = false;
            for (int h = 1; h < p.rings().size(); h++) {
                // a point on the edge of a hole is on the boundary of the zone: inside
                if (locate(p.rings().get(h), lon, lat) == INSIDE) {
                    inHole = true;
                    break;
                }
            }
            if (!inHole) {
                return true;
            }
        }
        return false;
    }

    private static final int OUTSIDE = 0;
    private static final int INSIDE = 1;
    private static final int ON_EDGE = 2;

    /** Even-odd ray casting with an explicit on-edge test. */
    static int locate(double[][] ring, double x, double y) {
        double[] xs = ring[0];
        double[] ys = ring[1];
        int n = xs.length;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = xs[i], yi = ys[i], xj = xs[j], yj = ys[j];
            if (onSegment(x, y, xi, yi, xj, yj)) {
                return ON_EDGE;
            }
            if ((yi > y) != (yj > y)) {
                double xCross = (xj - xi) * (y - yi) / (yj - yi) + xi;
                if (x < xCross) {
                    inside = !inside;
                }
            }
        }
        return inside ? INSIDE : OUTSIDE;
    }

    private static boolean onSegment(double px, double py, double ax, double ay, double bx, double by) {
        double cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
        if (Math.abs(cross) > EPS * Math.max(1.0, Math.hypot(bx - ax, by - ay))) {
            return false;
        }
        return px >= Math.min(ax, bx) - EPS && px <= Math.max(ax, bx) + EPS
                && py >= Math.min(ay, by) - EPS && py <= Math.max(ay, by) + EPS;
    }

    private static double[][] toArrays(List<double[]> ring) {
        int n = ring.size();
        boolean closed = n > 1 && ring.get(0)[0] == ring.get(n - 1)[0] && ring.get(0)[1] == ring.get(n - 1)[1];
        int size = closed ? n : n + 1;
        double[] xs = new double[size];
        double[] ys = new double[size];
        for (int i = 0; i < n; i++) {
            xs[i] = ring.get(i)[0];
            ys[i] = ring.get(i)[1];
        }
        if (!closed) {
            xs[n] = xs[0];
            ys[n] = ys[0];
        }
        return new double[][] {xs, ys};
    }

    static boolean crossesMeridian(double[] lons) {
        for (int i = 1; i < lons.length; i++) {
            if (Math.abs(lons[i] - lons[i - 1]) > 180.0) {
                return true;
            }
        }
        return false;
    }

    private static void unwrap(double[] lons) {
        for (int i = 0; i < lons.length; i++) {
            if (lons[i] < 0) {
                lons[i] += 360.0;
            }
        }
    }
}
