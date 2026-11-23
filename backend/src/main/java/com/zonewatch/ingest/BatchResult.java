package com.zonewatch.ingest;

import java.util.List;

public record BatchResult(int received, int accepted, int duplicates, int rejected, List<Rejection> rejections) {
}
