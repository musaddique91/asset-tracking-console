package com.zonewatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IngestControllerTest {

    @Test
    void countsOnlyNonEmptyLines() {
        assertThat(IngestController.nonEmptyLines("a\n\n  \r\nb\r\nc")).containsExactly("a", "b", "c");
        assertThat(IngestController.nonEmptyLines("a\n")).containsExactly("a");
        assertThat(IngestController.nonEmptyLines("")).isEmpty();
    }
}
