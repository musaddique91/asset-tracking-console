package com.zonewatch.alert;

import com.zonewatch.auth.CurrentUser;
import com.zonewatch.common.ApiException;
import com.zonewatch.common.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert lifecycle. Each command locks the alert row (SELECT ... FOR UPDATE), decides on the
 * locked state, changes it and appends the audit entry, all in one transaction (R4, R5).
 * A repeated command that changes nothing returns 200 and writes no audit entry.
 */
@Service
public class AlertService {

    public record Page(List<Alert> items, String nextCursor) {
    }

    private final AlertRepository repo;
    private final AlertMetrics metrics;

    public AlertService(AlertRepository repo, AlertMetrics metrics) {
        this.repo = repo;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public Page list(AlertStatus status, String assetId, String zoneId, int limit, String cursor) {
        long after = decodeCursor(cursor);
        List<AlertRepository.Row> rows = repo.page(status, assetId, zoneId, after, limit + 1);
        boolean more = rows.size() > limit;
        List<AlertRepository.Row> page = more ? rows.subList(0, limit) : rows;
        String next = more ? encodeCursor(page.get(page.size() - 1).seq()) : null;
        return new Page(page.stream().map(AlertRepository.Row::alert).toList(), next);
    }

    @Transactional(readOnly = true)
    public Alert get(String alertId) {
        return repo.find(alertId).orElseThrow(() -> ApiException.notFound("alert"));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> audit(String alertId) {
        if (repo.find(alertId).isEmpty()) {
            throw ApiException.notFound("alert");
        }
        return repo.audit(alertId);
    }

    @Transactional
    public Alert claim(String alertId, CurrentUser user) {
        Alert a = repo.lock(alertId).orElseThrow(() -> ApiException.notFound("alert"));
        switch (a.status()) {
            case OPEN -> {
                Alert claimed = repo.claim(alertId, user.username());
                repo.audit(alertId, user.username(), "CLAIMED", AlertStatus.OPEN, AlertStatus.CLAIMED);
                metrics.command("claim", "changed");
                return claimed;
            }
            case CLAIMED -> {
                if (user.username().equals(a.claimedBy())) {
                    metrics.command("claim", "unchanged");
                    return a;
                }
                metrics.command("claim", "conflict");
                throw new ApiException(ProblemCode.ALERT_ALREADY_CLAIMED, "the alert is already claimed by " + a.claimedBy());
            }
            default -> {
                metrics.command("claim", "conflict");
                throw new ApiException(ProblemCode.INVALID_STATE, "the alert is resolved");
            }
        }
    }

    @Transactional
    public Alert release(String alertId, CurrentUser user) {
        Alert a = repo.lock(alertId).orElseThrow(() -> ApiException.notFound("alert"));
        if (a.status() != AlertStatus.CLAIMED) {
            metrics.command("release", "conflict");
            throw new ApiException(ProblemCode.INVALID_STATE, "only a claimed alert can be released; it is " + a.status());
        }
        if (!user.username().equals(a.claimedBy()) && !user.isSupervisor()) {
            metrics.command("release", "forbidden");
            throw new ApiException(ProblemCode.NOT_ALERT_OWNER, "the alert is owned by " + a.claimedBy());
        }
        Alert released = repo.release(alertId);
        repo.audit(alertId, user.username(), "RELEASED", AlertStatus.CLAIMED, AlertStatus.OPEN);
        metrics.command("release", "changed");
        return released;
    }

    @Transactional
    public Alert resolve(String alertId, CurrentUser user, String note) {
        if (note == null || note.isBlank()) {
            throw ApiException.validation("note is required");
        }
        if (note.codePointCount(0, note.length()) > 500) {
            throw ApiException.validation("note must be at most 500 characters");
        }
        Alert a = repo.lock(alertId).orElseThrow(() -> ApiException.notFound("alert"));
        switch (a.status()) {
            case OPEN -> {
                metrics.command("resolve", "conflict");
                throw new ApiException(ProblemCode.INVALID_STATE, "claim the alert before resolving it");
            }
            case RESOLVED -> {
                if (user.username().equals(a.resolvedBy())) {
                    metrics.command("resolve", "unchanged");
                    return a;
                }
                metrics.command("resolve", "conflict");
                throw new ApiException(ProblemCode.INVALID_STATE, "the alert was already resolved by " + a.resolvedBy());
            }
            default -> {
                if (!user.username().equals(a.claimedBy()) && !user.isSupervisor()) {
                    metrics.command("resolve", "forbidden");
                    throw new ApiException(ProblemCode.NOT_ALERT_OWNER, "the alert is owned by " + a.claimedBy());
                }
                Alert resolved = repo.resolve(alertId, user.username(), note);
                repo.audit(alertId, user.username(), "RESOLVED", AlertStatus.CLAIMED, AlertStatus.RESOLVED);
                metrics.command("resolve", "changed");
                return resolved;
            }
        }
    }

    static String encodeCursor(long seq) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("a:" + seq).getBytes(StandardCharsets.UTF_8));
    }

    static long decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        try {
            String s = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!s.startsWith("a:")) {
                throw new IllegalArgumentException();
            }
            long seq = Long.parseLong(s.substring(2));
            if (seq < 0) {
                throw new IllegalArgumentException();
            }
            return seq;
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("cursor is not valid");
        }
    }
}
