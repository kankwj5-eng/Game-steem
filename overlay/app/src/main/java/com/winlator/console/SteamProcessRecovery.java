package com.winlator.console;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/** Only reclaim guests explicitly tagged for this app's Steam root. Never infer ownership by name. */
public final class SteamProcessRecovery {
    public static final String ENV = "DROIDDECK_STEAM_RUNTIME";
    private static final int MAX_FILE = 64 * 1024;
    private SteamProcessRecovery() {}

    public static int recover(File proc, int uid, int self, String scope, IntConsumer kill) {
        if (scope == null || scope.isEmpty()) return 0;
        File[] entries = proc.listFiles();
        if (entries == null) return 0;
        int reclaimed = 0;
        for (File entry : entries) {
            int pid;
            try { pid = Integer.parseInt(entry.getName()); }
            catch (NumberFormatException ignored) { continue; }
            if (pid <= 1 || pid == self) continue;
            String identity = identity(entry, uid, scope);
            if (identity == null) continue;
            // Recheck uid, tag and starttime immediately before signalling; exiting/reused PIDs are skipped.
            if (!identity.equals(identity(entry, uid, scope))) continue;
            try { kill.accept(pid); reclaimed++; }
            catch (RuntimeException ignored) { /* Already exited or denied: leave it to Android. */ }
        }
        return reclaimed;
    }

    private static String identity(File entry, int uid, String scope) {
        try {
            boolean owned = false;
            for (String line : read(new File(entry, "status")).split("\n")) {
                if (line.startsWith("Uid:")) owned = RuntimeStartupPolicy.ownsUidLine(line, uid);
            }
            if (!owned) return null;
            boolean tagged = false;
            for (String value : read(new File(entry, "environ")).split("\u0000")) {
                if (value.equals(ENV + "=" + scope)) tagged = true;
            }
            if (!tagged) return null;
            String stat = read(new File(entry, "stat"));
            int end = stat.lastIndexOf(')');
            if (end < 0 || !stat.startsWith(entry.getName() + " (")) return null;
            String[] fields = stat.substring(end + 2).trim().split("\\s+");
            if (fields.length < 20 || "Z".equals(fields[0]) || "X".equals(fields[0])) return null;
            return Long.toString(Long.parseLong(fields[19]));
        }
        catch (Exception ignored) { return null; }
    }

    private static String read(File file) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_FILE) throw new java.io.IOException("proc row too large");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
