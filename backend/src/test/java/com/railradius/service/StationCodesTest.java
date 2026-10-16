package com.railradius.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StationCodesTest {

    private final StationCodes codes = new StationCodes();

    @Test
    void eachFeedsIdFormatGivesTheSameUicCode() {
        assertThat(codes.of("switzerland:8714621")).contains("8714621");
        assertThat(codes.of("france:StopArea:OCE87146217")).as("check digit dropped").contains("8714621");
        assertThat(codes.of("italy:IT::StopPlace:otherTRENITALIA:830006040")).contains("8306040");
        assertThat(codes.of("italy-trenord:S06040")).contains("8306040");
        assertThat(codes.of("italy:IT::StopPlace:railTRENITALIA:810003877")).contains("8103877");
        assertThat(codes.of("austria:Pat:47:3877")).contains("8103877");
        assertThat(codes.of("slovakia:5616446")).contains("5616446");
        assertThat(codes.of("czechia:czptt:stop:SK:16446")).contains("5616446");
        assertThat(codes.of("spain:50500")).contains("7150500");
        assertThat(codes.of("spain-ouigo:007150500")).contains("7150500");
    }

    @Test
    void idsWithoutAKnownCodeFormatHaveNoCode() {
        assertThat(codes.of("germany:de:16073:8010309")).as("no rule for the source").isEmpty();
        assertThat(codes.of("france:StopPoint:OCETGV INOUI-71043075")).as("other ID type of the feed").isEmpty();
        assertThat(codes.of("czechia:czptt:stop:DE:10232")).as("not a UIC number").isEmpty();
    }
}
