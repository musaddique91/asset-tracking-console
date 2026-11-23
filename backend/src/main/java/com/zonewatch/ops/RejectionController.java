package com.zonewatch.ops;

import com.zonewatch.common.Params;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RejectionController {

    private final RejectionService rejections;

    public RejectionController(RejectionService rejections) {
        this.rejections = rejections;
    }

    @GetMapping("/api/v1/rejections")
    public RejectionService.Page list(@RequestParam(required = false) String code,
                                      @RequestParam(required = false) String limit,
                                      @RequestParam(required = false) String cursor) {
        return rejections.list(code == null || code.isBlank() ? null : code, Params.limit(limit, 100, 500), cursor);
    }
}
