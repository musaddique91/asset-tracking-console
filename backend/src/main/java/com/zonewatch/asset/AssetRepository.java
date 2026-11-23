package com.zonewatch.asset;

import com.zonewatch.common.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AssetRepository {

    private static final String POSITION_COLUMNS =
            "asset_id, kind, report_id, observed_at, lat, lon, speed_kn, heading_deg, source";

    private final JdbcClient jdbc;

    public AssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Uses the GiST index on point(lon, lat). Fetches limit + 1 rows to know whether the answer is truncated. */
    public List<AssetPosition> inBox(Bbox box, int fetch) {
        String where = box.crossesAntimeridian()
                ? "(point(lon, lat) <@ box(point(:minLon, :minLat), point(180, :maxLat))"
                  + " OR point(lon, lat) <@ box(point(-180, :minLat), point(:maxLon, :maxLat)))"
                : "point(lon, lat) <@ box(point(:minLon, :minLat), point(:maxLon, :maxLat))";
        return jdbc.sql("SELECT " + POSITION_COLUMNS + " FROM asset_position WHERE " + where
                        + " ORDER BY asset_id LIMIT :fetch")
                .param("minLon", box.minLon()).param("minLat", box.minLat())
                .param("maxLon", box.maxLon()).param("maxLat", box.maxLat())
                .param("fetch", fetch)
                .query(AssetRepository::position).list();
    }

    public Optional<AssetPosition> find(String assetId) {
        return jdbc.sql("SELECT " + POSITION_COLUMNS + " FROM asset_position WHERE asset_id = :id")
                .param("id", assetId).query(AssetRepository::position).optional();
    }

    public List<AssetPosition> all(int fetch) {
        return jdbc.sql("SELECT " + POSITION_COLUMNS + " FROM asset_position ORDER BY asset_id LIMIT :fetch")
                .param("fetch", fetch).query(AssetRepository::position).list();
    }

    public List<TrackPoint> track(String assetId, Instant from, Instant to, int fetch) {
        // conditions are added only when given, so the (asset_id, observed_at, report_id) index range is exact
        StringBuilder sql = new StringBuilder("SELECT report_id, observed_at, lat, lon, source FROM report WHERE asset_id = :id");
        if (from != null) {
            sql.append(" AND observed_at >= CAST(:from AS timestamptz)");
        }
        if (to != null) {
            sql.append(" AND observed_at <= CAST(:to AS timestamptz)");
        }
        sql.append(" ORDER BY observed_at, report_id LIMIT :fetch");
        var spec = jdbc.sql(sql.toString()).param("id", assetId).param("fetch", fetch);
        if (from != null) {
            spec = spec.param("from", from.toString());
        }
        if (to != null) {
            spec = spec.param("to", to.toString());
        }
        return spec.query((rs, n) -> new TrackPoint(rs.getString(1), Times.instant(rs.getTimestamp(2)),
                rs.getDouble(3), rs.getDouble(4), rs.getString(5))).list();
    }

    static AssetPosition position(ResultSet rs, int n) throws SQLException {
        return new AssetPosition(rs.getString(1), rs.getString(2), rs.getString(3), Times.instant(rs.getTimestamp(4)),
                rs.getDouble(5), rs.getDouble(6), (Double) rs.getObject(7), (Double) rs.getObject(8), rs.getString(9));
    }
}
