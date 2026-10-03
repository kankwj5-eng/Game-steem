package com.winlator;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.winlator.console.BootstrapStep;
import com.winlator.console.ConsoleBootstrapController;
import com.winlator.console.ConsoleLogStore;
import com.winlator.core.AppUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConsoleLauncherActivity extends AppCompatActivity implements ConsoleBootstrapController.Listener, ConsoleLogStore.Listener {
    private static final int STORAGE_PERMISSION_REQUEST = 7101;

    private final Map<String, TextView> statusViews = new HashMap<>();
    private final Map<String, ProgressBar> progressViews = new HashMap<>();

    private ConsoleBootstrapController controller;
    private Button startButton;
    private TextView steamState;
    private TextView deviceInfo;
    private TextView currentStage;
    private TextView currentDetail;
    private TextView currentPercent;
    private ProgressBar currentProgress;
    private TextView logView;
    private TextView logPath;
    private ScrollView logScroll;
    private View errorActions;
    private boolean permissionsBlocked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.console_launcher_activity);

        bindStep("permissions", R.id.StepPermissionsStatus, R.id.StepPermissionsProgress);
        bindStep("system", R.id.StepSystemStatus, R.id.StepSystemProgress);
        bindStep("gpu", R.id.StepGpuStatus, R.id.StepGpuProgress);
        bindStep("container", R.id.StepContainerStatus, R.id.StepContainerProgress);
        bindStep("steam", R.id.StepSteamStatus, R.id.StepSteamProgress);
        bindStep("launch", R.id.StepLaunchStatus, R.id.StepLaunchProgress);

        startButton = findViewById(R.id.BTStartSteam);
        steamState = findViewById(R.id.TVSteamState);
        deviceInfo = findViewById(R.id.TVDeviceInfo);
        currentStage = findViewById(R.id.TVCurrentStage);
        currentDetail = findViewById(R.id.TVCurrentDetail);
        currentPercent = findViewById(R.id.TVCurrentPercent);
        currentProgress = findViewById(R.id.PBCurrent);
        logView = findViewById(R.id.TVConsoleLog);
        logPath = findViewById(R.id.TVLogPath);
        logScroll = findViewById(R.id.SVConsoleLog);
        errorActions = findViewById(R.id.ErrorActions);

        ConsoleLogStore.initialize(this);
        ConsoleLogStore.addListener(this);
        logPath.setText(ConsoleLogStore.getSessionFilePath());

        controller = new ConsoleBootstrapController(this, this);

        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> {
            if (permissionsBlocked) requestRequiredPermissions();
            else controller.startSteam();
        });

        findViewById(R.id.BTDiagnostics).setOnClickListener(v -> {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            ConsoleLogStore.info("Diagnóstico visible en la mitad derecha.");
        });

        findViewById(R.id.BTCloseDiagnostics).setOnClickListener(v -> errorActions.setVisibility(View.GONE));

        findViewById(R.id.BTRetry).setOnClickListener(v -> {
            errorActions.setVisibility(View.GONE);
            controller.retry();
        });

        findViewById(R.id.BTControls).setOnClickListener(v -> {
            steamState.setText("Controles táctiles: configuración automática por juego");
            ConsoleLogStore.info("Controles táctiles: se conservará el motor de perfiles de Winlator.");
        });

        showDeviceBasics();
        if (!requestRequiredPermissions()) startBootstrap();
    }

    private void showDeviceBasics() {
        long totalMb = 0;
        try {
            ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mi);
                totalMb = mi.totalMem / (1024L * 1024L);
            }
        }
        catch (Exception ignored) {}

        String ram = totalMb > 0 ? " · " + totalMb + " MB RAM" : "";
        deviceInfo.setText(Build.MANUFACTURER + " " + Build.MODEL + ram);
    }

    private boolean requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
            permissionsBlocked = false;
            if (controller != null) controller.setPermissionsReady(true, "Almacenamiento preparado por Android");
            return false;
        }

        List<String> pending = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pending.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pending.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (pending.isEmpty()) {
            permissionsBlocked = false;
            if (controller != null) controller.setPermissionsReady(true, "Almacenamiento autorizado");
            return false;
        }

        permissionsBlocked = true;
        controller.setPermissionsReady(false, "Autoriza el almacenamiento para acceder a juegos y descargas");
        startButton.setEnabled(true);
        startButton.setText("CONCEDER PERMISOS");
        ActivityCompat.requestPermissions(this, pending.toArray(new String[0]), STORAGE_PERMISSION_REQUEST);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != STORAGE_PERMISSION_REQUEST) return;

        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;

        if (granted) {
            permissionsBlocked = false;
            controller.setPermissionsReady(true, "Almacenamiento autorizado");
            startBootstrap();
        }
        else {
            permissionsBlocked = true;
            controller.setPermissionsReady(false, "Permiso rechazado · toca para volver a solicitarlo");
            startButton.setEnabled(true);
            startButton.setText("CONCEDER PERMISOS");
            showErrorActions();
        }
    }

    private void startBootstrap() {
        permissionsBlocked = false;
        startButton.setEnabled(false);
        startButton.setText("PREPARANDO…");
        controller.prepare();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppUtils.hideSystemUI(this);
        if (controller != null && !permissionsBlocked) controller.refreshAfterResume();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ConsoleBootstrapController.REQUEST_RUNTIME && controller != null) {
            controller.handleRuntimeResult(resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        ConsoleLogStore.removeListener(this);
        super.onDestroy();
    }

    private void bindStep(String id, int statusId, int progressId) {
        statusViews.put(id, findViewById(statusId));
        progressViews.put(id, findViewById(progressId));
    }

    @Override
    public void onStep(BootstrapStep step) {
        runOnUiThread(() -> {
            TextView text = statusViews.get(step.id);
            ProgressBar progress = progressViews.get(step.id);
            if (text == null || progress == null) return;

            String glyph;
            int color;
            switch (step.state) {
                case DONE:
                    glyph = "✓";
                    color = R.color.console_ok;
                    break;
                case ERROR:
                    glyph = "!";
                    color = R.color.console_error;
                    break;
                case RUNNING:
                    glyph = "●";
                    color = R.color.console_accent;
                    break;
                default:
                    glyph = "○";
                    color = R.color.console_muted;
                    break;
            }

            text.setText(glyph + "  " + step.title + "\n" + step.detail);
            text.setTextColor(ContextCompat.getColor(this, color));
            progress.setProgress(step.progress);
            progress.setVisibility(step.state == BootstrapStep.State.RUNNING ? View.VISIBLE : View.GONE);

            if (step.state == BootstrapStep.State.RUNNING || step.state == BootstrapStep.State.ERROR) {
                currentStage.setText(step.title);
                currentDetail.setText(step.detail);
                currentProgress.setProgress(step.progress);
                currentPercent.setText(step.progress + "%");
            }
            else if (step.state == BootstrapStep.State.DONE && "launch".equals(step.id)) {
                currentStage.setText("Listo");
                currentDetail.setText(step.detail);
                currentProgress.setProgress(100);
                currentPercent.setText("100%");
            }

            if (step.state == BootstrapStep.State.ERROR) {
                startButton.setEnabled(true);
                startButton.setText("REINTENTAR");
                steamState.setText("Se produjo un error · el detalle está visible a la derecha");
                showErrorActions();
            }
        });
    }

    @Override
    public void onReady(boolean steamInstalled) {
        runOnUiThread(() -> {
            if (steamInstalled) {
                startButton.setEnabled(true);
                startButton.setText("INICIAR STEAM");
                steamState.setText("Steam está listo");
            }
            else {
                startButton.setEnabled(false);
                startButton.setText("INSTALANDO STEAM…");
                steamState.setText("Primera configuración automática en curso");
            }
        });
    }

    @Override
    public void onDeviceInfo(String gpu, String route) {
        runOnUiThread(() -> deviceInfo.setText(gpu + " · " + route));
    }

    @Override
    public void onLogChanged(String fullLog) {
        runOnUiThread(() -> {
            logView.setText(fullLog.isEmpty() ? "Esperando actividad…" : fullLog);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void showErrorActions() {
        errorActions.setVisibility(View.VISIBLE);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}
