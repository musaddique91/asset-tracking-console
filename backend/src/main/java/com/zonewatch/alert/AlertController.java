package com.zonewatch.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.zonewatch.auth.CurrentUser;
import com.zonewatch.common.ApiException;
import com.zonewatch.common.Params;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public AlertService.Page list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String assetId,
                                  @RequestParam(required = false) String zoneId,
                                  @RequestParam(required = false) String limit,
                                  @RequestParam(required = false) String cursor) {
        AlertStatus s = null;
        if (status != null) {
            try {
                s = AlertStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("status must be OPEN, CLAIMED or RESOLVED");
            }
        }
        return alerts.list(s, blankToNull(assetId), blankToNull(zoneId), Params.limit(limit, 100, 500), cursor);
    }

    @GetMapping("/{alertId}")
    public Alert get(@PathVariable String alertId) {
        return alerts.get(alertId);
    }

    @GetMapping("/{alertId}/audit")
    public Map<String, List<AuditEntry>> audit(@PathVariable String alertId) {
        return Map.of("items", alerts.audit(alertId));
    }

    @PostMapping("/{alertId}/claim")
    public Alert claim(@PathVariable String alertId) {
        return alerts.claim(alertId, CurrentUser.get());
    }

    @PostMapping("/{alertId}/release")
    public Alert release(@PathVariable String alertId) {
        return alerts.release(alertId, CurrentUser.get());
    }

    @PostMapping("/{alertId}/resolve")
    public Alert resolve(@PathVariable String alertId, @RequestBody(required = false) JsonNode body) {
        JsonNode note = body == null ? null : body.get("note");
        if (note != null && !note.isNull() && !note.isTextual()) {
            throw ApiException.validation("note must be a string");
        }
        return alerts.resolve(alertId, CurrentUser.get(), note == null || note.isNull() ? null : note.textValue());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
