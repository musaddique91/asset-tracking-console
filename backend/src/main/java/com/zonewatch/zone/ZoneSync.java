package com.zonewatch.zone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonewatch.config.ZwProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Writes the loaded zones to the database before the web server accepts traffic (alerts reference them). */
@Component
public class ZoneSync implements SmartInitializingSingleton {

    private final ZwProperties props;
    private final ZoneRegistry registry;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ZoneSync(ZwProperties props, ZoneRegistry registry, JdbcClient jdbc, ObjectMapper mapper) {
        this.props = props;
        this.registry = registry;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!props.serving()) {
            return;
        }
        for (Zone z : registry.all().values()) {
            String geojson;
            try {
                geojson = mapper.writeValueAsString(z.geometry());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            jdbc.sql("""
                    INSERT INTO zone (zone_id, name, zone_class, geojson) VALUES (:id, :n, :c, :g)
                    ON CONFLICT (zone_id) DO UPDATE SET name = EXCLUDED.name, zone_class = EXCLUDED.zone_class,
                        geojson = EXCLUDED.geojson, loaded_at = now()
                    WHERE (zone.name, zone.zone_class, zone.geojson) IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.zone_class, EXCLUDED.geojson)
                    """)
                    .param("id", z.zoneId()).param("n", z.name()).param("c", z.zoneClass()).param("g", geojson)
                    .update();
        }
    }
}
