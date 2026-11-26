package org.wyominglegacy.core;

/** Tiny logging hook so the core stays free of Android classes. */
public final class Dbg {
    public interface Sink {
        void d(String msg);
    }

    public static volatile Sink sink;

    private Dbg() {
    }

    public static void d(String msg) {
        Sink s = sink;
        if (s != null) {
            s.d(msg);
        }
    }
}
