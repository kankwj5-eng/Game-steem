package com.winlator.console;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class SteamInstallationTest {
    private static final String SOURCE = "https://example.test/steam-legacy.7z";
    private static final String SHA = "expected-sha";
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("steam-recovery-").toFile();
        try {
            String error = InstallerError.userMessage(new OutOfMemoryError("Failed to allocate a 268435472 byte allocation"));
            check(error.contains("Memoria insuficiente") && !error.contains("268435472"), "OOM explained in Spanish");
            check(InstallerError.userMessage(new Exception(new String(new char[500]).replace('\0', 'x'))).length() <= 180,
                    "long errors have a bounded user-facing summary");
            File live = new File(root, "Steam");
            File stage = new File(root, "stage/Steam");
            File backup = new File(root, "previous");
            payload(live);
            check(!ready(live), "steam.exe alone must not be ready");
            write(new File(live, SteamInstallation.MARKER), "source=" + SOURCE + "\nsha256=wrong\n");
            check(!ready(live), "wrong package SHA rejected");
            write(new File(live, SteamInstallation.MARKER), "source=wrong\nsha256=" + SHA + "\n");
            check(!ready(live), "wrong source rejected");
            write(new File(live, SteamInstallation.MARKER), "source=" + SOURCE + "\nsha256=" + SHA + "\n");
            check(ready(live), "valid historical receipt remains usable");
            new File(live, "SteamUI.dll").delete();
            check(!ready(live), "missing client library rejected");
            write(new File(live, "userdata/save"), "saved game");
            payload(stage);
            SteamInstallation.recover(live, stage, backup, SOURCE, SHA);
            check(new File(live, "userdata/save").isFile(), "interrupted extraction cannot erase live data");
            check(!ready(live), "staging without receipt cannot be promoted");
            SteamInstallation.writeReceipt(stage, SOURCE, SHA);
            SteamInstallation.recover(live, stage, backup, SOURCE, SHA);
            check(ready(live) && !stage.exists(), "completed extraction automatically promoted");
            check(new File(backup, "userdata/save").isFile(), "previous data retained during repair");
            payload(stage);
            SteamInstallation.writeReceipt(stage, SOURCE, SHA);
            write(new File(live, "userdata/keep"), "keep");
            SteamInstallation.recover(live, stage, backup, SOURCE, SHA);
            check(new File(live, "userdata/keep").isFile() && stage.exists(), "valid installation never replaced");
            delete(live);
            SteamInstallation.recover(live, stage, backup, SOURCE, SHA);
            check(ready(live), "resume after first promotion rename");
            delete(backup);
            check(live.renameTo(backup), "simulate interrupted rename");
            SteamInstallation.recover(live, stage, backup, SOURCE, SHA);
            check(ready(live) && !backup.exists(), "rollback when completed staging absent");
            write(new File(live, SteamInstallation.MARKER), "format=2\nstate=extracting\nsource=" + SOURCE + "\nsha256=" + SHA + "\n");
            check(!ready(live), "incomplete receipt state rejected");
            write(new File(live, SteamInstallation.MARKER), "source=" + SOURCE + "\nsha256=\\uZZZZ\n");
            check(!ready(live), "damaged receipt safely rejected");
            payload(live);
            SteamInstallation.writeReceipt(live, SOURCE, SHA);
            try (java.io.RandomAccessFile binary = new java.io.RandomAccessFile(new File(live, "steam.exe"), "rw")) {
                binary.setLength(300 * 1024L);
            }
            check(!ready(live), "changed core size rejected even above executable minimum");
            try (FileOutputStream out = new FileOutputStream(new File(live, "steam.exe"))) { out.write(0); }
            check(!ready(live), "truncated executable rejected");
            System.out.println("Steam recovery: " + checks + " checks passed");
        }
        finally { delete(root); }
    }

    private static boolean ready(File dir) { return SteamInstallation.isReady(dir, SOURCE, SHA); }
    private static void check(boolean result, String reason) {
        if (!result) throw new AssertionError(reason);
        checks++;
    }
    private static void payload(File directory) throws Exception {
        for (String name : new String[]{"steam.exe", "steamclient.dll", "SteamUI.dll", "bin/steamservice.exe",
                "bin/cef/cef.win7x64/steamwebhelper.exe", "bin/cef/cef.win7x64/libcef.dll"}) {
            File file = new File(directory, name);
            file.getParentFile().mkdirs();
            byte[] bytes = new byte[256 * 1024]; bytes[0] = 'M'; bytes[1] = 'Z';
            Files.write(file.toPath(), bytes);
        }
    }
    private static void write(File file, String text) throws Exception {
        file.getParentFile().mkdirs(); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static void delete(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        Files.deleteIfExists(file.toPath());
    }
}
