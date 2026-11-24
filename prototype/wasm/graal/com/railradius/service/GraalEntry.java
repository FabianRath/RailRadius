package com.railradius.service;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Prototype only (#8): {@link PrototypeSearch} for GraalVM Web Image. @JS.Export is not implemented yet, so main puts
 * one function on globalThis.railSearch; it takes {op, file, today, starts, start, transfers} and returns a string.
 */
public final class GraalEntry {

    private GraalEntry() {
    }

    public static void main(String[] args) {
        export(GraalEntry::handle);
    }

    @JS(args = {"handler"}, value = "globalThis.railSearch = handler;")
    private static native void export(Function<JSObject, JSString> handler);

    private static JSString handle(JSObject request) {
        try {
            String op = request.get("op", String.class);
            String result = switch (op) {
                // Web Image has no typed-array interop: the file comes as a string of one char per byte
                case "load" -> PrototypeSearch.load(request.get("file", String.class).getBytes(StandardCharsets.ISO_8859_1),
                        request.get("today", String.class));
                case "snapshot" -> PrototypeSearch.snapshot(request.get("starts", String.class));
                case "search" -> PrototypeSearch.search(request.get("start", String.class),
                        request.get("transfers", Integer.class));
                default -> throw new IllegalArgumentException("Unknown op " + op);
            };
            return JSString.of(result);
        } catch (IOException | RuntimeException e) {
            // A thrown Java exception reaches JavaScript as an opaque proxy, so the caller gets the trace as text
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            return JSString.of("ERROR " + trace);
        }
    }
}
