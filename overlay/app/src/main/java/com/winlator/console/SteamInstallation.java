package com.winlator.console;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Small readiness contract and recoverable directory promotion, independent of Android. */
final class SteamInstallation {
    static final String MARKER = ".droiddeck-source";
    private static final String[] CORE = {"steam.exe", "steamclient.dll", "SteamUI.dll",
            "bin/steamservice.exe", "bin/cef/cef.win7x64/steamwebhelper.exe",
            "bin/cef/cef.win7x64/libcef.dll"};

    private SteamInstallation() {}

    static boolean isReady(File directory, String source, String sha256) {
        File marker = new File(directory, MARKER);
        if (!marker.isFile() || marker.length() > 4096L) return false;
        Properties receipt = new Properties();
        try (FileInputStream in = new FileInputStream(marker)) {
            receipt.load(in);
            if (!source.equals(receipt.getProperty("source"))
                    || !sha256.equalsIgnoreCase(receipt.getProperty("sha256", ""))) return false;
            // The old receipt was also written last. Keep valid M20 installations usable.
            String format = receipt.getProperty("format");
            if (format != null && (!"2".equals(format)
                    || !"complete".equals(receipt.getProperty("state")))) return false;
            for (String path : CORE) {
                File file = new File(directory, path);
                if (!file.isFile() || file.length() < 256 * 1024L) return false;
                if (format != null && !Long.toString(file.length()).equals(receipt.getProperty("size." + path))) {
                    return false;
                }
                try (FileInputStream binary = new FileInputStream(file)) {
                    if (binary.read() != 'M' || binary.read() != 'Z') return false;
                }
            }
            return true;
        }
        catch (IOException | IllegalArgumentException error) {
            return false;
        }
    }

    static void writeReceipt(File directory, String source, String sha256) throws IOException {
        if (!directory.isDirectory()) throw new IOException("Carpeta Steam ausente");
        File temporary = new File(directory, MARKER + ".tmp");
        StringBuilder text = new StringBuilder("format=2\nstate=complete\nsource=" + source + "\nsha256=" + sha256 + "\n");
        for (String path : CORE) {
            text.append("size.").append(path).append('=').append(new File(directory, path).length()).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temporary.renameTo(new File(directory, MARKER))) {
            throw new IOException("No se pudo confirmar el marcador de Steam");
        }
        if (!isReady(directory, source, sha256)) throw new IOException("Cliente Steam incompleto");
    }

    /** Resume a completed extraction or roll back a promotion interrupted after its first rename. */
    static void recover(File target, File staged, File previous, String source, String sha256) throws IOException {
        if (isReady(target, source, sha256)) return;
        if (isReady(staged, source, sha256)) {
            promote(target, staged, previous, source, sha256);
        }
        else if (!target.exists() && isReady(previous, source, sha256)) {
            if (!previous.renameTo(target)) throw new IOException("No se pudo restaurar Steam anterior");
        }
    }

    static void promote(File target, File staged, File previous, String source, String sha256) throws IOException {
        if (isReady(target, source, sha256)) return;
        if (!isReady(staged, source, sha256)) throw new IOException("Extracción Steam sin verificar");
        if (target.exists()) {
            // Never delete a previous installation: it may contain games, saves or login data.
            File retained = previous;
            int suffix = 0;
            while (retained.exists()) retained = new File(previous.getPath() + "." + (++suffix));
            if (!target.renameTo(retained)) throw new IOException("No se pudo conservar Steam anterior");
        }
        if (!staged.renameTo(target)) throw new IOException("No se pudo activar Steam; se recuperará al reiniciar");
    }
}
