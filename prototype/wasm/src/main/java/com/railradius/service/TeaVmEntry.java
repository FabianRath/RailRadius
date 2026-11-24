package com.railradius.service;

import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

import java.io.IOException;

/** Prototype only (#8): {@link PrototypeSearch} exported to JavaScript by TeaVM. */
public final class TeaVmEntry {

    private TeaVmEntry() {
    }

    public static void main(String[] args) {
    }

    @JSExport
    public static String load(Int8Array file, String today) throws IOException {
        return PrototypeSearch.load(file.copyToJavaArray(), today);
    }

    @JSExport
    public static String snapshot(String startIdsCsv) throws IOException {
        return PrototypeSearch.snapshot(startIdsCsv);
    }

    @JSExport
    public static String search(String startId, int maxTransfers) {
        return PrototypeSearch.search(startId, maxTransfers);
    }
}
