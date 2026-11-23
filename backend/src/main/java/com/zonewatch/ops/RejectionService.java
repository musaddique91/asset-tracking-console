package com.zonewatch.ops;

import com.zonewatch.common.ApiException;
import com.zonewatch.common.Times;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** R6: rejected lines can be inspected later - what, why and when. Newest first, cursor paged. */
@Service
public class RejectionService {

    public record RejectedLine(Instant receivedAt, String requestId, int line, String code, String detail, String raw,
                               boolean rawTruncated) {
    }

    public record Page(List<RejectedLine> items, String nextCursor) {
    }

    private final JdbcClient jdbc;

    public RejectionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page list(String code, int limit, String cursor) {
        long before = Long.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            try {
                before = Long.parseLong(cursor);
            } catch (NumberFormatException e) {
                throw ApiException.validation("cursor is not valid");
            }
        }
        record Row(long seq, RejectedLine line) {
        }
        String sql = "SELECT rejected_seq, received_at, request_id, line_no, code, detail, raw, raw_truncated "
                + "FROM rejected_line WHERE rejected_seq < :before" + (code == null ? "" : " AND code = :code")
                + " ORDER BY rejected_seq DESC LIMIT :fetch";
        var spec = jdbc.sql(sql).param("before", before).param("fetch", limit + 1);
        if (code != null) {
            spec = spec.param("code", code);
        }
        List<Row> rows = spec.query((rs, n) -> new Row(rs.getLong(1), new RejectedLine(Times.instant(rs.getTimestamp(2)),
                rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8)))).list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        return new Page(page.stream().map(Row::line).toList(), more ? String.valueOf(page.get(page.size() - 1).seq()) : null);
    }
}
