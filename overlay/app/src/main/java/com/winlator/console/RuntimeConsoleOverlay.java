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

    public void ready(String message) {
        if (closed) return;
        stage("Listo", message, 100);
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
