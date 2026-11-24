package org.teavm.classlib.java.util;

/** Prototype (#8): java.util.StringJoiner, which TeaVM's class library lacks. Enough for SearchSnapshot. */
public final class TStringJoiner {

    private final String delimiter;
    private final StringBuilder joined = new StringBuilder();
    private boolean empty = true;

    public TStringJoiner(CharSequence delimiter) {
        this.delimiter = delimiter.toString();
    }

    public TStringJoiner add(CharSequence element) {
        if (!empty) joined.append(delimiter);
        joined.append(element);
        empty = false;
        return this;
    }

    @Override
    public String toString() {
        return joined.toString();
    }
}
