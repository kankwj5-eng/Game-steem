package com.winlator.console;

import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;

import com.winlator.XServerDisplayActivity;
import com.winlator.winhandler.OnGetProcessInfoListener;
import com.winlator.winhandler.ProcessInfo;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Window;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SteamRuntimeWatchdog implements OnGetProcessInfoListener {
    private final XServerDisplayActivity activity;
    private final RuntimeConsoleOverlay overlay;
    private final WinHandler winHandler;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final ArrayList<ProcessInfo> processes = new ArrayList<>();

    private boolean running;
    private long startedAt;
    private long lastRxBytes = -1L;
    private long lastRxSampleAt;
    private double lastRxRate = -1.0;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            sampleNetwork();
            winHandler.listProcesses();
            publishFallbackIfNeeded();
            handler.postDelayed(this, 1000L);
        }
    };

    public SteamRuntimeWatchdog(XServerDisplayActivity activity, RuntimeConsoleOverlay overlay) {
        this.activity = activity;
        this.overlay = overlay;
        this.winHandler = activity.getWinHandler();
    }

    public void start() {
        if (running) return;
        running = true;
        startedAt = android.os.SystemClock.elapsedRealtime();
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        lastRxSampleAt = startedAt;
        winHandler.setOnGetProcessInfoListener(this);
        ConsoleLogStore.info("WATCHDOG Steam iniciado.");
        handler.post(ticker);
    }

    public void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (winHandler.getOnGetProcessInfoListener() == this) {
            winHandler.setOnGetProcessInfoListener(null);
        }
        ConsoleLogStore.info("WATCHDOG Steam detenido.");
    }

    @Override
    public void onGetProcessInfo(int index, int count, ProcessInfo processInfo) {
        synchronized (lock) {
            if (!running) return;
            if (index == 0) processes.clear();
            if (processInfo != null) processes.add(processInfo);
            if (count == 0 || index >= count - 1) {
                publishSnapshot();
            }
        }
    }

    private void sampleNetwork() {
        long now = android.os.SystemClock.elapsedRealtime();
        long rx = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        if (rx >= 0 && lastRxBytes >= 0 && now > lastRxSampleAt) {
            lastRxRate = Math.max(0.0, (rx - lastRxBytes) * 1000.0 / (now - lastRxSampleAt));
        }
        lastRxBytes = rx;
        lastRxSampleAt = now;
    }

    private void publishFallbackIfNeeded() {
        synchronized (lock) {
            if (!processes.isEmpty()) return;
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - startedAt;
        overlay.waitingTelemetry(
                elapsed,
                "Esperando respuesta del entorno Windows",
                "[todavía sin lista de procesos]",
                0,
                0,
                lastRxBytes,
                lastRxRate
        );
    }

    private void publishSnapshot() {
        ArrayList<ProcessInfo> snapshot = new ArrayList<>(processes);
        boolean steam = false;
        boolean helper = false;
        boolean setup = false;
        int windowCount = 0;
        StringBuilder names = new StringBuilder();

        XServer xServer = activity.getXServer();
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            for (ProcessInfo info : snapshot) {
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
            int limit = Math.min(snapshot.size(), 6);
            for (int i = 0; i < limit; i++) {
                if (i > 0) names.append(", ");
                names.append(snapshot.get(i).name);
            }
        }

        String diagnosis;
        if (steam && helper && windowCount > 0) {
            diagnosis = "Steam ya tiene procesos e interfaz; esperando mapeo final";
        }
        else if (steam && helper && lastRxRate >= 8 * 1024.0) {
            diagnosis = "Steam está descargando o actualizando componentes";
        }
        else if (steam && helper) {
            diagnosis = "Steam y steamwebhelper están activos; XServer aún sin ventana";
        }
        else if (steam) {
            diagnosis = "steam.exe está activo; esperando steamwebhelper y la interfaz";
        }
        else if (setup) {
            diagnosis = "El instalador/servicio de Steam sigue trabajando";
        }
        else if (!snapshot.isEmpty()) {
            diagnosis = "Wine está activo, pero steam.exe todavía no aparece";
        }
        else {
            diagnosis = "Sin procesos Windows visibles todavía";
        }

        long elapsed = android.os.SystemClock.elapsedRealtime() - startedAt;
        overlay.waitingTelemetry(
                elapsed,
                diagnosis,
                names.toString(),
                snapshot.size(),
                windowCount,
                lastRxBytes,
                lastRxRate
        );

        if (elapsed >= 60_000L && !steam && lastRxRate < 4096.0) {
            ConsoleLogStore.warn("WATCHDOG: 60 s sin steam.exe y casi sin tráfico. Posible bloqueo antes de iniciar Steam.");
        }
        else if (elapsed >= 60_000L && steam && !helper && lastRxRate < 4096.0) {
            ConsoleLogStore.warn("WATCHDOG: steam.exe activo sin steamwebhelper tras 60 s. Posible bloqueo de interfaz.");
        }
        else if (elapsed >= 90_000L && steam && helper && windowCount == 0 && lastRxRate < 4096.0) {
            ConsoleLogStore.warn("WATCHDOG: Steam y steamwebhelper activos sin ventana XServer tras 90 s.");
        }
    }

    private boolean isInteresting(String name) {
        return name.contains("steam")
                || name.contains("wine")
                || name.contains("services")
                || name.contains("explorer")
                || name.contains("winhandler");
    }
}
