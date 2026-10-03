package com.railreach.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StopIdsTest {

    @Test
    void sourceIsThePartBeforeTheFirstSeparator() {
        assertThat(StopIds.source("germany:8011160")).isEqualTo("germany");
        assertThat(StopIds.source("belgien:gs:nmbssncb:8101073")).isEqualTo("belgien");
    }

    @Test
    void ofPrefixesTheRawIdWithTheSource() {
        assertThat(StopIds.of("austria", "at:43:3")).isEqualTo("austria:at:43:3");
        assertThat(StopIds.source(StopIds.of("austria", "at:43:3"))).isEqualTo("austria");
    }
}
