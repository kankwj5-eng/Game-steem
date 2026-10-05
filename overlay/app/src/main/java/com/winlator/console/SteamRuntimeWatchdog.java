package com.winlator.console;

import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.core.ProcessHelper;
import com.winlator.winhandler.OnGetProcessInfoListener;
import com.winlator.winhandler.ProcessInfo;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Window;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SteamRuntimeWatchdog implements OnGetProcessInfoListener {
    private static final long TICK_MS = 1000L;
    private static final long FILE_SCAN_MS = 5000L;
    private static final long PROCESS_SCAN_MS = 2000L;
    private static final long STALL_MS = 60_000L;

    private final XServerDisplayActivity activity;
    private final RuntimeConsoleOverlay overlay;
    private final WinHandler winHandler;
    private final String purpose;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final ArrayList<ProcessInfo> windowsProcesses = new ArrayList<>();
    private final ExecutorService fileScanner = Executors.newSingleThreadExecutor();

    private volatile boolean running;
    private volatile boolean fileScanRunning;
    private volatile boolean processScanRunning;
    private volatile boolean winHandlerResponded;
    private volatile long lastActivityAt;
    private long startedAt;
    private long lastProcessScanAt;

    private long rxBaseline = -1L;
    private long lastRxBytes = -1L;
    private long lastRxSampleAt;
    private double rxRate = -1.0;
    private long sessionRxBytes;

    private volatile FileSnapshot fileSnapshot = new FileSnapshot();
    private long lastFileScanAt;
    private long previousFileBytes = -1L;
    private long previousNewestMtime;
    private long previousFileSampleAt;
    private volatile double fileWriteRate;

    private volatile String linuxProcessSummary = "[sin datos]";
    private volatile int linuxProcessCount;
    private final ProcessCpuActivity cpuActivity = new ProcessCpuActivity();
    private boolean wasStalled;
    private boolean startupLogsCollected;
    private String lastLinuxProcessSignature = "";
    private String lastWindowsProcessSignature = "";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;

            long now = android.os.SystemClock.elapsedRealtime();
            sampleNetwork(now);
            if (now - lastProcessScanAt >= PROCESS_SCAN_MS) {
                lastProcessScanAt = now;
                if (!processScanRunning) {
                    processScanRunning = true;
                    if (!submitBackground(() -> {
                        try { if (running) sampleLinuxProcesses(); }
                        catch (Exception error) { ConsoleLogStore.warn("Monitor de procesos: " + error.getMessage()); }
                        finally { processScanRunning = false; }
                    })) processScanRunning = false;
                }
                winHandler.listProcesses();
            }
            scheduleFileScan(now);
            publishCombinedTelemetry();

            handler.postDelayed(this, TICK_MS);
        }
    };

    public SteamRuntimeWatchdog(XServerDisplayActivity activity, RuntimeConsoleOverlay overlay, String purpose) {
        this.activity = activity;
        this.overlay = overlay;
        this.winHandler = activity.getWinHandler();
        this.purpose = purpose == null ? "" : purpose;
    }

    public void start() {
        if (running) return;

        running = true;
        startedAt = android.os.SystemClock.elapsedRealtime();
        lastActivityAt = startedAt;

        rxBaseline = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        lastRxBytes = rxBaseline;
        lastRxSampleAt = startedAt;

        winHandler.setOnGetProcessInfoListener(this);

        ConsoleLogStore.info("WATCHDOG iniciado · propósito=" + purpose + " · procesos cada " + PROCESS_SCAN_MS + " ms · archivos cada " + FILE_SCAN_MS + " ms");
        handler.post(ticker);
    }

    public void stop() {
        if (!running) return;

        running = false;
        handler.removeCallbacksAndMessages(null);
        fileScanner.shutdownNow();

        if (winHandler.getOnGetProcessInfoListener() == this) {
            winHandler.setOnGetProcessInfoListener(null);
        }

        ConsoleLogStore.info("WATCHDOG detenido.");
    }

    @Override
    public void onGetProcessInfo(int index, int count, ProcessInfo processInfo) {
        synchronized (lock) {
            if (!running) return;

            winHandlerResponded = true;
            if (index == 0) windowsProcesses.clear();
            if (processInfo != null) windowsProcesses.add(processInfo);

            if (count == 0 || index >= count - 1) {
                markProcessActivityIfChanged();
            }
        }
    }

    private void sampleNetwork(long now) {
        long rx = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        if (rx >= 0 && lastRxBytes >= 0 && now > lastRxSampleAt) {
            long delta = Math.max(0L, rx - lastRxBytes);
            rxRate = delta * 1000.0 / Math.max(1L, now - lastRxSampleAt);
            if (delta > 1024L) lastActivityAt = now;
        }

        if (rx >= 0 && rxBaseline >= 0) {
            sessionRxBytes = Math.max(0L, rx - rxBaseline);
        }

        lastRxBytes = rx;
        lastRxSampleAt = now;
    }

    private void sampleLinuxProcesses() {
        List<ProcessHelper.PStat> children = ProcessHelper.getChildProcesses();
        StringBuilder names = new StringBuilder();
        int count = 0;
        boolean cpuBusy = false;
        cpuActivity.beginSample();

        for (ProcessHelper.PStat process : children) {
            String name = process.name == null ? "" : process.name;
            String lower = name.toLowerCase(Locale.US);
            if (process.guestProcess || lower.contains("box64") || lower.contains("steam")
                    || lower.contains("wine") || lower.contains("winhandler")) {
                if (process.state == ProcessHelper.PState.ZOMBIE || process.state == ProcessHelper.PState.DEAD) continue;
                count++;
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/" + process.pid + "/stat"))) {
                    String stat = reader.readLine();
                    if (stat != null) cpuBusy |= cpuActivity.observe(stat);
                }
                catch (java.io.IOException ignored) { /* Exited or not readable. */ }
                if (names.length() >= 220) continue;
                if (names.length() > 0) names.append(", ");
                names.append(name);
                if (names.length() > 220) {
                    names.append("…");
                }
            }
        }
        cpuActivity.endSample();
        if (cpuBusy) lastActivityAt = android.os.SystemClock.elapsedRealtime();

        linuxProcessCount = count;
        linuxProcessSummary = names.length() > 0 ? names.toString() : "[sin procesos guest visibles]";

        String signature = linuxProcessCount + ":" + linuxProcessSummary;
        if (!signature.equals(lastLinuxProcessSignature)) {
            lastLinuxProcessSignature = signature;
            lastActivityAt = android.os.SystemClock.elapsedRealtime();
        }
    }

    private void markProcessActivityIfChanged() {
        StringBuilder signature = new StringBuilder();
        for (ProcessInfo info : windowsProcesses) {
            signature.append(info.pid).append(':').append(info.name).append(';');
        }

        String value = signature.toString();
        if (!value.equals(lastWindowsProcessSignature)) {
            lastWindowsProcessSignature = value;
            lastActivityAt = android.os.SystemClock.elapsedRealtime();
        }
    }

    private void scheduleFileScan(long now) {
        if (fileScanRunning || now - lastFileScanAt < FILE_SCAN_MS) return;

        fileScanRunning = true;
        lastFileScanAt = now;
        if (!submitBackground(() -> {
            try {
                FileSnapshot snapshot = scanSteamFiles();
                long finishedAt = android.os.SystemClock.elapsedRealtime();

                if (previousFileBytes >= 0 && previousFileSampleAt > 0L && finishedAt > previousFileSampleAt) {
                    long delta = Math.max(0L, snapshot.totalBytes - previousFileBytes);
                    fileWriteRate = delta * 1000.0 / Math.max(1L, finishedAt - previousFileSampleAt);
                }
                else {
                    fileWriteRate = 0.0;
                }

                boolean changed = previousFileBytes < 0
                        || snapshot.totalBytes != previousFileBytes
                        || snapshot.newestMtime > previousNewestMtime;

                if (changed && previousFileBytes >= 0) {
                    lastActivityAt = finishedAt;
                    ConsoleLogStore.info(
                            "DISCO · " + formatBytes(snapshot.totalBytes)
                                    + " · último: " + snapshot.newestPath
                    );
                }

                previousFileBytes = snapshot.totalBytes;
                previousNewestMtime = snapshot.newestMtime;
                previousFileSampleAt = finishedAt;
                fileSnapshot = snapshot;
            }
            catch (Exception e) {
                ConsoleLogStore.warn("Monitor de archivos: " + e.getMessage());
            }
            finally {
                fileScanRunning = false;
            }
        })) fileScanRunning = false;
    }

    private boolean submitBackground(Runnable task) {
        if (!running) return false;
        try { fileScanner.execute(task); return true; }
        catch (java.util.concurrent.RejectedExecutionException ignored) {
            // A window callback may stop the monitor while the UI tick submits work.
            return false;
        }
    }

    private FileSnapshot scanSteamFiles() {
        long sampleStartedAt = android.os.SystemClock.elapsedRealtime();
        Container container = activity.getContainer();
        File driveC = new File(container.getRootDir(), ".wine/drive_c");

        ArrayList<File> roots = new ArrayList<>();
        // Sample mutable client activity, not thousands of static assets or installed games.
        File steam = new File(driveC, "Program Files (x86)/Steam");
        for (String path : new String[]{"logs", "config", "userdata", "appcache", "package", "steamapps/downloading"}) {
            addIfExists(roots, new File(steam, path));
        }

        FileSnapshot snapshot = new FileSnapshot();
        snapshot.sampleStartedAt = sampleStartedAt;
        int[] budget = {1024};

        for (File root : roots) {
            scanDirectory(root, driveC, snapshot, 0, budget);
            if (budget[0] <= 0) break;
        }

        if (snapshot.newestPath == null || snapshot.newestPath.isEmpty()) {
            snapshot.newestPath = "[sin archivo reciente]";
        }
        return snapshot;
    }

    private void addIfExists(ArrayList<File> roots, File file) {
        if (file.exists()) roots.add(file);
    }

    private void scanDirectory(File file, File driveC, FileSnapshot out, int depth, int[] budget) {
        if (file == null || budget[0] <= 0 || depth > 8) return;

        if (file.isFile()) {
            budget[0]--;
            out.fileCount++;
            out.totalBytes += Math.max(0L, file.length());

            long modified = file.lastModified();
            if (modified >= out.newestMtime) {
                out.newestMtime = modified;
                out.newestPath = toWinePath(file, driveC);
            }
            return;
        }

        if (!file.isDirectory()) return;

        String name = file.getName();
        if (depth > 0 && ("windows".equalsIgnoreCase(name)
                || "$Recycle.Bin".equalsIgnoreCase(name))) {
            return;
        }

        File[] children = file.listFiles();
        if (children == null) return;

        for (File child : children) {
            scanDirectory(child, driveC, out, depth + 1, budget);
            if (budget[0] <= 0) return;
        }
    }

    private String toWinePath(File file, File driveC) {
        try {
            String base = driveC.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.startsWith(base)) {
                String relative = path.substring(base.length()).replace('/', '\\');
                return "C:" + relative;
            }
        }
        catch (Exception ignored) {}
        return file.getName();
    }

    private void publishCombinedTelemetry() {
        if (!running) return;

        ArrayList<ProcessInfo> windowsSnapshot;
        synchronized (lock) {
            windowsSnapshot = new ArrayList<>(windowsProcesses);
        }

        boolean steam = false;
        boolean helper = false;
        boolean setup = false;
        int windowCount = 0;
        StringBuilder names = new StringBuilder();

        XServer xServer = activity.getXServer();
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            for (ProcessInfo info : windowsSnapshot) {
                String name = info.name == null ? "" : info.name.toLowerCase(Locale.US);
                if ("steam.exe".equals(name)) steam = true;
                if ("steamwebhelper.exe".equals(name)) helper = true;
                if (name.contains("steamsetup") || name.contains("steamservice")) setup = true;

                Window window = xServer.windowManager.findWindowWithProcessId(info.pid);
                if (window != null) windowCount++;

                if (isInteresting(name)) {
                    if (names.length() > 0) names.append(", ");
                    names.append(info.name).append("[").append(info.getFormattedMemoryUsage()).append("]");
                }
            }
        }
        catch (Exception e) {
            ConsoleLogStore.warn("WATCHDOG XServer: " + e.getMessage());
        }

        if (names.length() == 0) {
            names.append(linuxProcessSummary);
        }

        FileSnapshot files = fileSnapshot;
        long now = android.os.SystemClock.elapsedRealtime();
        long elapsed = now - startedAt;
        long idleMs = Math.max(0L, now - lastActivityAt);
        boolean stalled = elapsed >= STALL_MS && idleMs >= STALL_MS && windowCount == 0;

        String diagnosis;
        if ("steam_install".equals(purpose) || "steam_fallback".equals(purpose)) {
            if (rxRate >= 8 * 1024.0 && fileWriteRate >= 8 * 1024.0) {
                diagnosis = "SteamSetup está descargando y escribiendo archivos";
            }
            else if (fileWriteRate >= 8 * 1024.0) {
                diagnosis = "SteamSetup está instalando archivos en el prefijo";
            }
            else if (rxRate >= 8 * 1024.0) {
                diagnosis = "SteamSetup está recibiendo datos de red";
            }
            else if (setup) {
                diagnosis = "SteamSetup / SteamService siguen activos";
            }
            else if (steam) {
                diagnosis = "steam.exe apareció; finalizando instalación";
            }
            else {
                diagnosis = "Wine mantiene el instalador activo";
            }
        }
        else {
            if (steam && helper && rxRate >= 8 * 1024.0) {
                diagnosis = "Steam está descargando o actualizando componentes";
            }
            else if (steam && helper && windowCount > 0) {
                diagnosis = "Steam ya tiene procesos y ventana; esperando mapeo final";
            }
            else if (steam && helper) {
                diagnosis = "Steam y steamwebhelper activos; XServer aún sin ventana";
            }
            else if (steam) {
                diagnosis = "steam.exe activo; esperando steamwebhelper y la interfaz";
            }
            else if (rxRate >= 8 * 1024.0 || fileWriteRate >= 8 * 1024.0) {
                diagnosis = "El runtime sigue trabajando antes de mostrar Steam";
            }
            else {
                diagnosis = "Wine/Box64 activos; esperando que Steam cree su interfaz";
            }
        }

        if (stalled) {
            diagnosis = "Sin actividad observable durante 60 s. Puedes ver la pantalla o volver y reintentar.";
        }

        overlay.waitingTelemetry(
                elapsed,
                diagnosis,
                names.toString(),
                windowsSnapshot.size(),
                linuxProcessCount,
                windowCount,
                sessionRxBytes,
                rxRate,
                files.fileCount,
                files.totalBytes,
                fileWriteRate,
                files.newestPath,
                idleMs,
                winHandlerResponded,
                stalled
        );

        if (stalled && !wasStalled) {
            ConsoleLogStore.warn("WATCHDOG · sin actividad observable · Windows=" + windowsSnapshot.size()
                    + " · Linux=" + linuxProcessCount + " · WinHandler=" + winHandlerResponded
                    + " · procesos=" + names);
        }
        wasStalled = stalled;
        if (!startupLogsCollected && (stalled || elapsed >= 180_000L)) {
            startupLogsCollected = true;
            submitBackground(this::collectStartupLogs);
        }
    }

    private void collectStartupLogs() {
        File steam = new File(activity.getContainer().getRootDir(), ".wine/drive_c/Program Files (x86)/Steam");
        for (String name : new String[]{"bootstrap_log.txt", "cef_log.txt"}) {
            File file = new File(steam, "logs/" + name);
            try {
                if (!file.getCanonicalPath().startsWith(steam.getCanonicalPath() + File.separator) || !file.isFile()) continue;
                try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
                    int size = (int)Math.min(4096L, input.length());
                    byte[] bytes = new byte[size];
                    input.seek(Math.max(0L, input.length() - size));
                    input.readFully(bytes);
                    ConsoleLogStore.warn("STEAM LOG · " + name + " · últimos " + size + " bytes\n"
                            + new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            catch (java.io.IOException error) { ConsoleLogStore.warn("No se pudo leer " + name + ": " + error.getMessage()); }
        }
    }

    private boolean isInteresting(String name) {
        return name.contains("steam")
                || name.contains("wine")
                || name.contains("services")
                || name.contains("explorer")
                || name.contains("winhandler");
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static final class FileSnapshot {
        long sampleStartedAt;
        int fileCount;
        long totalBytes;
        long newestMtime;
        String newestPath = "[sin archivo reciente]";
    }
}
