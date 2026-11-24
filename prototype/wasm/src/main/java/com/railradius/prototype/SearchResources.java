package com.railradius.prototype;

import org.teavm.classlib.ResourceSupplier;
import org.teavm.classlib.ResourceSupplierContext;

/** Prototype (#8): the classpath files TeaVM embeds, which the station name index reads when a store loads. */
public final class SearchResources implements ResourceSupplier {

    @Override
    public String[] supplyResources(ResourceSupplierContext context) {
        return new String[] { "station-aliases.txt", "station-codes.txt" };
    }
}
