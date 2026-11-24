package com.railradius.service;

/**
 * What a search is: where it starts, how many changes it may make and how long it may wait at each. Options outside
 * the API's bounds can't be built, so the result cache, the rate limit and the warmer only ever see allowed values
 * (B41, B42). Equal options are the same search: they share a stored result and a running search.
 */
public record SearchOptions(String startId, int maxTransfers, int minTransferMins, int maxTransferMins) {

    public static final int MAX_TRANSFERS = 5;
    // The longest wait at a change: longer windows make a search many times slower than the rate limit charges for
    public static final int MAX_TRANSFER_WAIT_MINUTES = 360;
    private static final int DEFAULT_MIN_TRANSFER_MINUTES = 0;
    private static final int DEFAULT_MAX_TRANSFER_MINUTES = 180;

    /** @throws SearchRefusal if the changes or the waits are out of range */
    public SearchOptions {
        if (maxTransfers < 0 || maxTransfers > MAX_TRANSFERS) {
            throw SearchRefusal.invalid("Changes must be between 0 and " + MAX_TRANSFERS);
        }
        if (minTransferMins < 0 || minTransferMins > maxTransferMins || maxTransferMins > MAX_TRANSFER_WAIT_MINUTES) {
            throw SearchRefusal.invalid("The wait at changes must be between 0 and " + MAX_TRANSFER_WAIT_MINUTES
                    + " minutes, the minimum not above the maximum");
        }
    }

    /** Options with the default for each wait the request leaves out. */
    public static SearchOptions of(String startId, int maxTransfers, Integer minTransferMins, Integer maxTransferMins) {
        return new SearchOptions(startId, maxTransfers,
                minTransferMins != null ? minTransferMins : DEFAULT_MIN_TRANSFER_MINUTES,
                maxTransferMins != null ? maxTransferMins : DEFAULT_MAX_TRANSFER_MINUTES);
    }

    SearchOptions withMaxTransfers(int transfers) {
        return new SearchOptions(startId, transfers, minTransferMins, maxTransferMins);
    }

    String label() {
        return startId + "-" + maxTransfers + "-" + minTransferMins + "-" + maxTransferMins;
    }
}
