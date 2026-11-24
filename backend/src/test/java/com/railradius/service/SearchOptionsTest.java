package com.railradius.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchOptionsTest {

    private static final String START_ID = "alpha:A1";

    @Test
    void missingWaitsFallBackToNoMinimumAndThreeHoursMaximum() {
        assertThat(SearchOptions.of(START_ID, 2, null, null))
                .isEqualTo(new SearchOptions(START_ID, 2, 0, 180));
    }

    @Test
    void givenWaitsAreKept() {
        assertThat(SearchOptions.of(START_ID, 2, 5, 60)).isEqualTo(new SearchOptions(START_ID, 2, 5, 60));
    }

    @Test
    void optionsAtTheBoundsAreAllowed() {
        assertThatCode(() -> new SearchOptions(START_ID, 0, 0, 0)).doesNotThrowAnyException();
        assertThatCode(() -> new SearchOptions(START_ID, SearchOptions.MAX_TRANSFERS,
                SearchOptions.MAX_TRANSFER_WAIT_MINUTES, SearchOptions.MAX_TRANSFER_WAIT_MINUTES))
                .doesNotThrowAnyException();
    }

    @Test
    void transfersOutsideTheBoundsAreRejected() {
        assertThatThrownBy(() -> new SearchOptions(START_ID, -1, 0, 180))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("Changes");
        assertThatThrownBy(() -> new SearchOptions(START_ID, SearchOptions.MAX_TRANSFERS + 1, 0, 180))
                .isInstanceOf(SearchRefusal.class);
    }

    @Test
    void waitsOutsideTheBoundsOrMinimumAboveMaximumAreRejected() {
        assertThatThrownBy(() -> new SearchOptions(START_ID, 1, -1, 180))
                .isInstanceOf(SearchRefusal.class).hasMessageContaining("wait at changes");
        assertThatThrownBy(() -> new SearchOptions(START_ID, 1, 0, SearchOptions.MAX_TRANSFER_WAIT_MINUTES + 1))
                .isInstanceOf(SearchRefusal.class);
        assertThatThrownBy(() -> new SearchOptions(START_ID, 1, 30, 20))
                .isInstanceOf(SearchRefusal.class);
    }

    @Test
    void moreTransfersKeepTheRestAndStayWithinTheBounds() {
        SearchOptions options = new SearchOptions(START_ID, 1, 5, 60);

        assertThat(options.withMaxTransfers(3)).isEqualTo(new SearchOptions(START_ID, 3, 5, 60));
        assertThatThrownBy(() -> options.withMaxTransfers(SearchOptions.MAX_TRANSFERS + 1))
                .isInstanceOf(SearchRefusal.class);
    }
}
