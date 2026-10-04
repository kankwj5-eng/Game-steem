package com.winlator.console;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class NativeSteamArchiveTest {
    public static void main(String[] args) throws Exception {
        File archive = new File(args[0]);
        File stage = new File(args[1]);
        Files.createDirectories(stage.toPath());
        long started = System.nanoTime();
        long[] index = NativeSteamArchive.inspect(archive.getPath());
        if (index[0] != 6181 || index[1] != 778291219L || index[2] != 1) {
            throw new AssertionError("Unexpected real Steam package index");
        }
        System.out.println("INDEX: " + index[0] + " entries, " + index[1] + " bytes, max block " + index[3]);
        long[] previous = {0, 0};
        long peak = NativeSteamArchive.extract(archive.getPath(), stage.getPath(),
                (path, files, bytes, total, nativePeak) -> {
                    if (bytes < previous[0] || files < previous[1] || bytes > total) throw new AssertionError("Invalid progress");
                    previous[0] = bytes; previous[1] = files;
                });
        if (previous[0] != index[1]) throw new AssertionError("Extraction stopped early");
        File steam = new File(stage, "Steam");
        SteamInstallation.writeReceipt(steam, "real-package", "real-sha");
        if (!SteamInstallation.isReady(steam, "real-package", "real-sha")) throw new AssertionError("Real Steam files not ready");
        if (peak > 640L * 1024 * 1024) throw new AssertionError("Native memory budget exceeded");
        System.out.println("EXTRACT: native allocation peak=" + peak + ", Java max heap=" + Runtime.getRuntime().maxMemory()
                + ", seconds=" + (System.nanoTime() - started) / 1e9);
        try {
            NativeSteamArchive.inspect("missing.7z");
            throw new AssertionError("Missing archive accepted");
        }
        catch (IOException expected) { System.out.println("Missing archive safely rejected"); }
        File unsafeStage = new File(stage.getParentFile(), "symlink-stage");
        Files.createDirectories(unsafeStage.toPath());
        File outside = new File(stage.getParentFile(), "outside-staging");
        Files.createDirectories(outside.toPath());
        Files.createSymbolicLink(new File(unsafeStage, "Steam").toPath(), outside.toPath());
        try {
            NativeSteamArchive.extract(archive.getPath(), unsafeStage.getPath(), (a,b,c,d,e) -> {});
            throw new AssertionError("Symlink escaped staging");
        }
        catch (IOException expected) {
            if (new File(outside, "steam.exe").exists()) throw new AssertionError("Files escaped staging");
            System.out.println("Symlink escape safely rejected");
        }
    }
}
