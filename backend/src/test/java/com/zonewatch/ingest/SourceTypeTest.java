package com.zonewatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SourceTypeTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "SAT-1, SATELLITE", "SAT-3, SATELLITE", "SAT, SATELLITE",
            "GND-1, GROUND", "GND-2, GROUND",
            "MOB-1, MOBILE",
            "XYZ-9, UNKNOWN", "sat-1, UNKNOWN", "'', UNKNOWN", "NULL, UNKNOWN", "S, UNKNOWN"})
    void derivedFromThePrefix(String source, SourceType expected) {
        assertThat(SourceType.of(source)).isEqualTo(expected);
    }
}
