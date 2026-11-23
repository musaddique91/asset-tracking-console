package com.zonewatch.stream;

import com.zonewatch.auth.CurrentUser;
import com.zonewatch.config.ZwProperties;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /api/v1/stream: server-sent events for the console (not part of the fixed contract).
 * On connect the client receives a snapshot of all active alerts (with versions); positions are
 * fetched through GET /api/v1/assets and merged by (observedAt, reportId), which is order-safe.
 */
@RestController
public class StreamController {

    private static final long TIMEOUT_MS = 15 * 60 * 1000L;

    private final StreamHub hub;
    private final StreamSnapshot snapshot;
    private final ZwProperties props;

    public StreamController(StreamHub hub, StreamSnapshot snapshot, ZwProperties props) {
        this.hub = hub;
        this.snapshot = snapshot;
        this.props = props;
    }

    @GetMapping(path = "/api/v1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        CurrentUser user = CurrentUser.get();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        StreamHub.Client client = hub.register(emitter, user.username());
        client.offer(new StreamHub.Message("hello", Map.of("instance", props.instance())));
        client.offer(new StreamHub.Message("alerts-snapshot", snapshot.activeAlerts()));
        return emitter;
    }
}
