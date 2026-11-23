package com.zonewatch.asset;

import com.zonewatch.common.Params;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assets;

    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    @GetMapping
    public AssetService.Page<AssetPosition> inBox(@RequestParam(required = false) String bbox,
                                                  @RequestParam(required = false) String limit) {
        int max = Params.limit(limit, 1000, 5000);
        return assets.inBox(Bbox.parse(bbox), max);
    }

    @GetMapping("/{assetId}")
    public AssetPosition get(@PathVariable String assetId) {
        return assets.get(assetId);
    }

    @GetMapping("/{assetId}/track")
    public AssetService.Page<TrackPoint> track(@PathVariable String assetId,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) String limit) {
        return assets.track(assetId, Params.instant("from", from), Params.instant("to", to),
                Params.limit(limit, 500, 5000));
    }
}
