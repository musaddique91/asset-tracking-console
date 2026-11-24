package com.zonewatch.asset;

import com.zonewatch.common.ApiException;
import com.zonewatch.ingest.SourceType;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetService {

    public record Page<T>(List<T> items, boolean truncated) {
    }

    private final AssetRepository repo;

    public AssetService(AssetRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Page<AssetPosition> inBox(Bbox box, int limit) {
        List<AssetPosition> rows = repo.inBox(box, limit + 1);
        return rows.size() > limit ? new Page<>(rows.subList(0, limit), true) : new Page<>(rows, false);
    }

    @Transactional(readOnly = true)
    public AssetPosition get(String assetId) {
        return repo.find(assetId).orElseThrow(() -> ApiException.notFound("asset"));
    }

    @Transactional(readOnly = true)
    public Page<TrackPoint> track(String assetId, Instant from, Instant to, SourceType sourceType, int limit) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.validation("from must not be after to");
        }
        if (repo.find(assetId).isEmpty()) {
            throw ApiException.notFound("asset");
        }
        List<TrackPoint> rows = repo.track(assetId, from, to, sourceType == null ? null : sourceType.name(), limit + 1);
        return rows.size() > limit ? new Page<>(rows.subList(0, limit), true) : new Page<>(rows, false);
    }
}
