package com.winlator.console;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConsoleLogStore {
    public interface Listener { void onLogChanged(String fullLog); }

    private static final int MAX_LINES = 900;
    private static final int FILE_LOG_FLUSH_BATCH = 32;
    private static final long FILE_LOG_FLUSH_MS = 750L;
    private static final long UI_NOTIFY_INTERVAL_MS = 120L;

    private static final Deque<String> lines = new ArrayDeque<>(MAX_LINES + 1);
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat SESSION_TIME = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    private static final SimpleDateFormat LINE_TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static File sessionFile;
    private static BufferedWriter sessionWriter;
    private static int bufferedFileLines;
    private static long lastDiskFlushAt;
    private static boolean uiNotifyScheduled;

    private static final Runnable uiNotifier = () -> {
        final String current;
        synchronized (ConsoleLogStore.class) {
            uiNotifyScheduled = false;
            current = snapshotLocked();
        }
        for (Listener listener : listeners) listener.onLogChanged(current);
    };

    private ConsoleLogStore() {}

    public static synchronized void initialize(Context context) {
        if (sessionFile != null) return;

        File dir = new File(context.getFilesDir(), "diagnostico");
        if (!dir.exists()) dir.mkdirs();

        String stamp = SESSION_TIME.format(new Date());
        sessionFile = new File(dir, "droiddeck-" + stamp + ".log");

        try {
            sessionWriter = new BufferedWriter(new FileWriter(sessionFile, true), 64 * 1024);
            lastDiskFlushAt = SystemClock.elapsedRealtime();
        }
        catch (Exception ignored) {
            sessionWriter = null;
        }

        append("INFO", "Diagnóstico iniciado: " + sessionFile.getName());
    }

    public static synchronized void append(String level, String message) {
        String safe = message == null ? "" : message.trim();
        if (safe.isEmpty()) return;

        String safeLevel = level == null || level.trim().isEmpty() ? "INFO" : level.trim();
        String line = LINE_TIME.format(new Date()) + "  " + safeLevel + "  " + safe;

        lines.addLast(line);
        while (lines.size() > MAX_LINES) lines.removeFirst();

        writeToDiskLocked(safeLevel, line);
        scheduleUiNotificationLocked();
    }

    private static void writeToDiskLocked(String level, String line) {
        if (sessionWriter == null) return;

        try {
            sessionWriter.write(line);
            sessionWriter.newLine();

            boolean highVolume = "FILE".equals(level) || "PKG".equals(level);
            if (highVolume) bufferedFileLines++;

            long now = SystemClock.elapsedRealtime();
            boolean flushNow = !highVolume
                    || bufferedFileLines >= FILE_LOG_FLUSH_BATCH
                    || now - lastDiskFlushAt >= FILE_LOG_FLUSH_MS;

            if (flushNow) {
                sessionWriter.flush();
                bufferedFileLines = 0;
                lastDiskFlushAt = now;
            }
        }
        catch (Exception ignored) {}
    }

    private static void scheduleUiNotificationLocked() {
        if (uiNotifyScheduled) return;
        uiNotifyScheduled = true;
        main.postDelayed(uiNotifier, UI_NOTIFY_INTERVAL_MS);
    }

    public static void info(String message) { append("INFO", message); }
    public static void ok(String message) { append("OK", message); }
    public static void warn(String message) { append("WARN", message); }
    public static void error(String message) { append("ERROR", message); }

    public static synchronized String snapshot() {
        return snapshotLocked();
    }

    private static String snapshotLocked() {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    public static synchronized void flush() {
        if (sessionWriter == null) return;
        try {
            sessionWriter.flush();
            bufferedFileLines = 0;
            lastDiskFlushAt = SystemClock.elapsedRealtime();
        }
        catch (Exception ignored) {}
    }

    public static synchronized String getSessionFilePath() {
        return sessionFile != null ? sessionFile.getAbsolutePath() : "";
    }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        if (!listeners.contains(listener)) listeners.add(listener);
        main.post(() -> listener.onLogChanged(snapshot()));
    }

    public static void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }
}
