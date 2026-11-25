package com.railradius.service;

/**
 * The {@link TripInfo} of each store trip as columns by trip index (#35). A map keyed by trip ID held a node, a key
 * string and a {@link TripInfo} per trip; columns hold three references, which share their strings when read from the
 * store file.
 */
final class TripInfos {

    static final TripInfos EMPTY = new TripInfos(0);

    private final String[] trainNumbers;
    private final String[] headsigns;
    private final String[] operators;

    TripInfos(int tripCount) {
        trainNumbers = new String[tripCount];
        headsigns = new String[tripCount];
        operators = new String[tripCount];
    }

    int size() {
        return trainNumbers.length;
    }

    void set(int trip, TripInfo info) {
        trainNumbers[trip] = info.trainNumber();
        headsigns[trip] = info.headsign();
        operators[trip] = info.operator();
    }

    TripInfo get(int trip) {
        return new TripInfo(trainNumbers[trip], headsigns[trip], operators[trip]);
    }
}
