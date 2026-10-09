package com.railreach.service;

/** Every test of {@link ReachabilityServiceTest}, the search semantics contract among them, on the compact engine. */
class CompactReachabilityServiceTest extends ReachabilityServiceTest {

    @Override
    String engine() {
        return SearchEngine.COMPACT;
    }
}
