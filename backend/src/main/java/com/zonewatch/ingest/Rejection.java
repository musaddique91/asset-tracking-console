package com.zonewatch.ingest;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** One rejected line, as answered to the caller (line, code, detail) and stored for inspection (raw). */
public record Rejection(int line, RejectionCode code, String detail, @JsonIgnore String raw) {
}
