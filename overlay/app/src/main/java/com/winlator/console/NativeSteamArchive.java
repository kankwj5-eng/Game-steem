package com.winlator.console;

import java.io.IOException;

/** The solid 7z decoder must not allocate its dictionary in Android's Java heap. */
public final class NativeSteamArchive {
    static { System.loadLibrary("steamarchive"); }
    private NativeSteamArchive() {}

    public interface Progress {
        void onProgress(String path, int files, long bytes, long total, long nativePeakBytes);
    }

    // entries, uncompressed bytes, contains Steam/steam.exe, largest solid block
    public static native long[] inspect(String archive) throws IOException;
    // Returns the peak decoder allocation; decoded solid blocks are reused across entries.
    public static native long extract(String archive, String stagingRoot, Progress progress) throws IOException;
}
