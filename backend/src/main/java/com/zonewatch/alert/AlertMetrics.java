package com.zonewatch.alert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AlertMetrics {

    private final Counter opened;
    private final MeterRegistry registry;

    public AlertMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.opened = Counter.builder("zw.alerts.opened").register(registry);
    }

    public void opened(int n) {
        opened.increment(n);
    }

    void command(String action, String result) {
        registry.counter("zw.alerts.commands", "action", action, "result", result).increment();
    }

    public double openedCount() {
        return opened.count();
    }
}
