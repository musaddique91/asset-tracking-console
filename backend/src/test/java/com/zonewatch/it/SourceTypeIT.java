package com.zonewatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.zonewatch.ingest.SourceType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CR-1 through the API: sourceType on every track point, the ?sourceType= filter. */
class SourceTypeIT extends AbstractIT {

    private String line(String reportId, String assetId, int second, String source) {
        String src = source == null ? "" : ",\"source\":\"" + source + "\"";
        return "{\"reportId\":\"" + reportId + "\",\"assetId\":\"" + assetId + "\",\"observedAt\":\""
                + T0.plusSeconds(second) + "\",\"lat\":" + OUT_LAT + ",\"lon\":" + OUT_LON + src + "}";
    }

    @Test
    void everyTrackPointHasItsSourceTypeAndTheTrackCanBeFiltered() {
        String a = asset();
        batch(List.of(line(a + "-1", a, 1, "SAT-2"), line(a + "-2", a, 2, "GND-1"), line(a + "-3", a, 3, "MOB-1"),
                line(a + "-4", a, 4, "XYZ-9"), line(a + "-5", a, 5, null), line(a + "-6", a, 6, "SAT-1")));

        var items = get("/api/v1/assets/" + a + "/track", "op01").body().get("items");
        assertThat(items.findValuesAsText("sourceType"))
                .containsExactly("SATELLITE", "GROUND", "MOBILE", "UNKNOWN", "UNKNOWN", "SATELLITE");

        var satellite = get("/api/v1/assets/" + a + "/track?sourceType=SATELLITE", "op01").body().get("items");
        assertThat(satellite.findValuesAsText("reportId")).containsExactly(a + "-1", a + "-6");
        var unknown = get("/api/v1/assets/" + a + "/track?sourceType=UNKNOWN", "op01").body().get("items");
        assertThat(unknown.findValuesAsText("reportId")).containsExactly(a + "-4", a + "-5");

        assertThat(get("/api/v1/assets/" + a + "/track?sourceType=RADAR", "op01").code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void javaAndSqlDeriveTheSameSourceType() {
        for (String source : new String[] {"SAT-1", "SAT", "GND-2", "MOB-1", "XYZ-9", "sat-1", "", "S", "MOBILE"}) {
            String sql = db.sql("SELECT report_source_type(:s)").param("s", source).query(String.class).single();
            assertThat(sql).as(source).isEqualTo(SourceType.of(source).name());
        }
        assertThat(db.sql("SELECT report_source_type(NULL)").query(String.class).single()).isEqualTo("UNKNOWN");
    }
}
