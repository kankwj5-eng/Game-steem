package com.winlator.console;

import android.app.Activity;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.winlator.R;

public final class RuntimeConsoleOverlay implements ConsoleLogStore.Listener {
    private final Activity activity;
    private final View root;
    private final TextView stage;
    private final TextView detail;
    private final TextView percent;
    private final TextView log;
    private final TextView stepWine;
    private final TextView stepGraphics;
    private final TextView stepAudio;
    private final TextView stepRuntime;
    private final TextView stepWindow;
    private final TextView telemetry;
    private final ProgressBar progress;
    private final ScrollView logScroll;
    private boolean closed;

    public RuntimeConsoleOverlay(Activity activity) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.droiddeck_runtime_overlay, null, false);
        this.stage = root.findViewById(R.id.TVRuntimeStage);
        this.detail = root.findViewById(R.id.TVRuntimeDetail);
        this.percent = root.findViewById(R.id.TVRuntimePercent);
        this.progress = root.findViewById(R.id.PBRuntime);
        this.log = root.findViewById(R.id.TVRuntimeLog);
        this.logScroll = root.findViewById(R.id.SVRuntimeLog);
        this.stepWine = root.findViewById(R.id.TVRuntimeWine);
        this.stepGraphics = root.findViewById(R.id.TVRuntimeGraphics);
        this.stepAudio = root.findViewById(R.id.TVRuntimeAudio);
        this.stepRuntime = root.findViewById(R.id.TVRuntimeBox64);
        this.stepWindow = root.findViewById(R.id.TVRuntimeWindow);
        this.telemetry = root.findViewById(R.id.TVRuntimeTelemetry);

        activity.addContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.bringToFront();
        ConsoleLogStore.addListener(this);
        stage("Motor", "Preparando entorno Windows", 4);
    }

    public void stage(String title, String message, int value) {
        if (closed) return;
        final int safe = Math.max(0, Math.min(100, value));
        activity.runOnUiThread(() -> {
            if (closed) return;
            stage.setText(title);
            detail.setText(message);
            percent.setText(safe + "%");
            progress.setIndeterminate(false);
            progress.setProgress(safe);
            updateSteps(safe);
            root.bringToFront();
        });
        ConsoleLogStore.info("ARRANQUE " + safe + "% · " + title + " · " + message);
    }

    private void updateSteps(int value) {
        setStep(stepWine, value >= 42, value >= 18, "Wine y prefijo");
        setStep(stepGraphics, value >= 64, value >= 43, "Vortek / Gladio");
        setStep(stepAudio, value >= 72, value >= 65, "Audio");
        setStep(stepRuntime, value >= 94, value >= 73, "Box64 + Wine");
        setStep(stepWindow, value >= 100, value >= 95, "Ventana de Steam");
    }

    private void setStep(TextView view, boolean done, boolean active, String label) {
        if (done) {
            view.setText("✓  " + label);
            view.setTextColor(activity.getResources().getColor(R.color.console_ok));
        }
        else if (active) {
            view.setText("●  " + label);
            view.setTextColor(activity.getResources().getColor(R.color.console_accent));
        }
        else {
            view.setText("○  " + label);
            view.setTextColor(activity.getResources().getColor(R.color.console_muted));
        }
    }

    public void waitingTelemetry(long elapsedMs, String diagnosis, String processSummary,
                                 int windowsProcessCount, int linuxProcessCount, int windowCount,
                                 long receivedBytes, double receiveBytesPerSecond,
                                 int fileCount, long fileBytes, double fileWriteBytesPerSecond,
                                 String lastFile, long idleMs, boolean winHandlerResponded,
                                 boolean stalled) {
        if (closed) return;

        final long seconds = Math.max(0L, elapsedMs / 1000L);
        final long idleSeconds = Math.max(0L, idleMs / 1000L);
        final String networkRate = formatRate(receiveBytesPerSecond);
        final String networkTotal = formatBytes(receivedBytes);
        final String diskRate = formatRate(fileWriteBytesPerSecond);
        final String diskTotal = formatBytes(fileBytes);
        final String safeFile = lastFile == null || lastFile.isEmpty() ? "[sin archivo reciente]" : lastFile;

        activity.runOnUiThread(() -> {
            if (closed) return;

            stage.setText(stalled ? "Steam · posible bloqueo real" : "Steam");
            detail.setText(diagnosis);
            percent.setText("95% · " + seconds + " s");
            progress.setIndeterminate(true);

            telemetry.setText(
                    "RED    " + networkRate + "   ·   sesión " + networkTotal + "\n" +
                    "DISCO (muestra)  " + diskRate + "   ·   " + fileCount + " archivos / " + diskTotal + "\n" +
                    "ÚLTIMO " + safeFile + "\n" +
                    "PROC   Windows " + windowsProcessCount +
                    " · Linux/Box64 " + linuxProcessCount +
                    " · ventanas " + windowCount + "\n" +
                    "WINHANDLER " + (winHandlerResponded ? "responde" : "sin respuesta todavía") +
                    "   ·   sin actividad " + idleSeconds + " s\n" +
                    "ACTIVOS " + (processSummary == null || processSummary.isEmpty() ? "[sin datos]" : processSummary)
            );

            if (stalled) {
                telemetry.setTextColor(activity.getResources().getColor(R.color.console_error));
            }
            else if (idleSeconds >= 30) {
                telemetry.setTextColor(activity.getResources().getColor(R.color.console_warn));
            }
            else {
                telemetry.setTextColor(Color.parseColor("#C9D3EB"));
            }

            root.bringToFront();
        });
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "n/d";
        if (bytes >= 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private String formatRate(double bytesPerSecond) {
        if (bytesPerSecond < 0) return "n/d";
        if (bytesPerSecond >= 1024.0 * 1024.0) return String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0);
        if (bytesPerSecond >= 1024.0) return String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0);
        return String.format(java.util.Locale.US, "%.0f B/s", bytesPerSecond);
    }

    public void ready(String message) {
        if (closed) return;
        stage("Listo", message, 100);
        activity.runOnUiThread(() -> telemetry.setText("Steam creó una ventana real en XServer."));
        activity.runOnUiThread(() -> {
            if (closed) return;
            root.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction(() -> {
                        if (!closed) root.setVisibility(View.GONE);
                    })
                    .start();
        });
    }

    public void close() {
        if (closed) return;
        closed = true;
        ConsoleLogStore.removeListener(this);
        activity.runOnUiThread(() -> {
            ViewGroup parent = (ViewGroup)root.getParent();
            if (parent != null) parent.removeView(root);
        });
    }

    @Override
    public void onLogChanged(String fullLog) {
        if (closed) return;
        activity.runOnUiThread(() -> {
            if (closed) return;
            log.setText(fullLog == null || fullLog.isEmpty() ? "Esperando actividad real del motor…" : fullLog);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}
