package com.zonewatch.zone;

import com.zonewatch.asset.AssetPosition;
import com.zonewatch.asset.AssetRepository;
import com.zonewatch.asset.Bbox;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZoneService {

    static final int MAX_PER_ZONE = 500;
    private static final int SCAN_LIMIT = 20000;

    private final ZoneRegistry zones;
    private final AssetRepository assets;

    public ZoneService(ZoneRegistry zones, AssetRepository assets) {
        this.zones = zones;
        this.assets = assets;
    }

    public List<Map<String, Object>> zones() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Zone z : zones.all().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zoneId", z.zoneId());
            m.put("name", z.name());
            m.put("zoneClass", z.zoneClass());
            m.put("crossesAntimeridian", z.shape().crossesAntimeridian());
            m.put("geometry", z.geometry());
            out.add(m);
        }
        return out;
    }

    public record Occupant(String assetId, String kind, java.time.Instant observedAt) {
    }

    public record Occupancy(String zoneId, String name, String zoneClass, int count, boolean truncated,
                            List<Occupant> assets) {
    }

    /** Which assets are inside which zone right now: the non-map view of the console. */
    @Transactional(readOnly = true)
    public List<Occupancy> occupancy() {
        List<Occupancy> out = new ArrayList<>();
        for (Zone z : zones.all().values()) {
            double[] b = z.shape().bounds();
            Bbox box = b[2] > 180 ? new Bbox(b[0], b[1], b[2] - 360, b[3]) : new Bbox(b[0], b[1], b[2], b[3]);
            List<Occupant> inside = new ArrayList<>();
            int count = 0;
            for (AssetPosition p : assets.inBox(box, SCAN_LIMIT)) {
                if (z.shape().contains(p.lon(), p.lat())) {
                    count++;
                    if (inside.size() < MAX_PER_ZONE) {
                        inside.add(new Occupant(p.assetId(), p.kind(), p.observedAt()));
                    }
                }
            }
            out.add(new Occupancy(z.zoneId(), z.name(), z.zoneClass(), count, count > inside.size(), inside));
        }
        return out;
    }
}
