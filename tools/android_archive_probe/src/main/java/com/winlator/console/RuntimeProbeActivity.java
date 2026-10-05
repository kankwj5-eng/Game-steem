package com.winlator.console;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import com.winlator.R;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual Android process runner and runtime wait UI; does not pretend to run ARM64 Wine. */
public final class RuntimeProbeActivity extends Activity {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!STARTED.compareAndSet(false, true)) return;
        ConsoleLogStore.initialize(this);
        AtomicInteger cancelled = new AtomicInteger();
        RuntimeConsoleOverlay overlay = new RuntimeConsoleOverlay(this, cancelled::incrementAndGet);
        overlay.waitingTelemetry(600000, "Sin actividad observable", "steam.exe", 1, 2, 0,
                0, 0, 0, 0, 0, "", 600000, false, true);
        getWindow().getDecorView().post(() -> {
            try {
                TextView percent = findViewById(R.id.TVRuntimePercent);
                if (!percent.getText().toString().equals("Espera · 600 s")) throw new AssertionError("Fake 95 percent remains");
                Rect rect = new Rect();
                for (int id : new int[]{R.id.BTRuntimeShow, R.id.BTRuntimeBack, R.id.BTRuntimeShare}) {
                    if (!findViewById(id).getGlobalVisibleRect(rect) || rect.height() < 35)
                        throw new AssertionError("Recovery action not reachable");
                }
                ScrollView status = findViewById(R.id.SVRuntimeStatus);
                status.fullScroll(View.FOCUS_DOWN);
                status.post(() -> {
                    try {
                        if (!findViewById(R.id.TVRuntimeTelemetry).getGlobalVisibleRect(new Rect()))
                            throw new AssertionError("Telemetry cannot be reached");
                        findViewById(R.id.BTRuntimeShow).performClick();
                        if (findViewById(R.id.TVRuntimePercent).isShown()) throw new AssertionError("Screen still covered");
                        if (cancelled.get() != 0) throw new AssertionError("Manual view cancels process");
                        findViewById(R.id.BTRuntimeBack).performClick();
                        findViewById(R.id.BTRuntimeBack).performClick();
                        if (cancelled.get() != 1) throw new AssertionError("Cancel callback must be once");
                        overlay.close();
                        save("runtime-ui.txt", "PASS runtime UI at ten minutes");
                    } catch (Throwable error) { save("runtime-ui.txt", "FAIL " + error); }
                });
            } catch (Throwable error) { save("runtime-ui.txt", "FAIL " + error); }
        });
        new Thread(() -> {
            try {
                RuntimeStartupTest.main(new String[]{"/system/bin/sh"});
                testProcessHelper();
                save("runtime-result.txt", "PASS real Android process output, exit and failure");
            } catch (Throwable error) { save("runtime-result.txt", "FAIL " + error); }
        }, "runtime-probe").start();
    }
    private void testProcessHelper() throws Exception {
        java.util.concurrent.CountDownLatch exited = new java.util.concurrent.CountDownLatch(1);
        java.util.List<String> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        com.winlator.core.Callback<String> listener = lines::add;
        com.winlator.core.ProcessHelper.addDebugCallback(listener);
        try {
            if (com.winlator.core.ProcessHelper.exec("/system/bin/echo droiddeck-probe", null, null,
                    status -> exited.countDown()) <= 0) throw new AssertionError("Null EnvVars launch");
            if (!exited.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Helper exit callback");
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!lines.contains("droiddeck-probe") && System.nanoTime() < until) Thread.sleep(20);
            if (!lines.contains("droiddeck-probe")) throw new AssertionError("Helper stdout");
            java.util.concurrent.CountDownLatch killed = new java.util.concurrent.CountDownLatch(1);
            int pid = com.winlator.core.ProcessHelper.exec("/system/bin/sleep 30", null, null, status -> killed.countDown());
            if (pid <= 0) throw new AssertionError("Child process launch");
            try {
                boolean found = false;
                for (com.winlator.core.ProcessHelper.PStat row : com.winlator.core.ProcessHelper.getChildProcesses()) {
                    if (row.pid == pid) found = true;
                    String uid = null;
                    for (String line : Files.readAllLines(new File("/proc/" + row.pid + "/status").toPath()))
                        if (line.startsWith("Uid:")) uid = line;
                    if (!RuntimeStartupPolicy.ownsUidLine(uid, android.os.Process.myUid()))
                        throw new AssertionError("Another app process entered snapshot");
                }
                if (!found) throw new AssertionError("Denied /proc row erased live child");
            }
            finally { com.winlator.core.ProcessHelper.killProcess(pid); }
            if (!killed.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Killed process exit");
        }
        finally { com.winlator.core.ProcessHelper.removeDebugCallback(listener); }
    }

    private void save(String name, String result) {
        try { Files.write(new File(getFilesDir(), name).toPath(), result.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { android.util.Log.e("RuntimeProbe", "Cannot write result", error); }
        android.util.Log.i("RuntimeProbe", result);
    }
}
