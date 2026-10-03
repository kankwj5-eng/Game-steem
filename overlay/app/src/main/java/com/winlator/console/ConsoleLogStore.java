package com.winlator.console;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConsoleLogStore {
    public interface Listener { void onLogChanged(String fullLog); }

    private static final int MAX_LINES = 900;
    private static final List<String> lines = new ArrayList<>();
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static File sessionFile;

    private ConsoleLogStore() {}

    public static synchronized void initialize(Context context) {
        if (sessionFile != null) return;
        File dir = new File(context.getFilesDir(), "diagnostico");
        if (!dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        sessionFile = new File(dir, "droiddeck-" + stamp + ".log");
        append("INFO", "Diagnóstico iniciado: " + sessionFile.getName());
    }

    public static synchronized void append(String level, String message) {
        String safe = message == null ? "" : message.trim();
        if (safe.isEmpty()) return;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = time + "  " + level + "  " + safe;
        lines.add(line);
        while (lines.size() > MAX_LINES) lines.remove(0);
        if (sessionFile != null) {
            try (FileWriter writer = new FileWriter(sessionFile, true)) {
                writer.write(line);
                writer.write('\n');
            }
            catch (Exception ignored) {}
        }
        final String snapshot = snapshot();
        main.post(() -> {
            for (Listener listener : listeners) listener.onLogChanged(snapshot);
        });
    }

    public static void info(String message) { append("INFO", message); }
    public static void ok(String message) { append("OK", message); }
    public static void warn(String message) { append("WARN", message); }
    public static void error(String message) { append("ERROR", message); }

    public static synchronized String snapshot() {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    public static synchronized String getSessionFilePath() {
        return sessionFile != null ? sessionFile.getAbsolutePath() : "";
    }

    public static void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        listener.onLogChanged(snapshot());
    }

    public static void removeListener(Listener listener) { listeners.remove(listener); }
}
