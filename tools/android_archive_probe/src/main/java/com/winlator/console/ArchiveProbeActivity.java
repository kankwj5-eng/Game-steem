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

/** Runs the real JNI decoder on Android, without replacing the ARM64 production engine. */
public final class ArchiveProbeActivity extends Activity {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.console_launcher_activity);
        TextView detail = findViewById(R.id.TVCurrentDetail);
        detail.setText(InstallerError.userMessage(new OutOfMemoryError("256 MB allocation")));
        findViewById(R.id.TVCurrentPercent).setVisibility(View.GONE);
        ScrollView main = findViewById(R.id.SVSteamMain);
        main.post(() -> {
            main.fullScroll(View.FOCUS_DOWN);
            main.post(() -> {
                try {
                    Rect bounds = new Rect();
                    if (!findViewById(R.id.BTStartSteam).getGlobalVisibleRect(bounds) || bounds.height() < 20)
                        throw new AssertionError("Steam button cannot be reached on short landscape screen");
                    Files.write(new File(getFilesDir(), "ui-result.txt").toPath(), "PASS\n".getBytes(StandardCharsets.UTF_8));
                } catch (Throwable error) { saveResult("FAIL UI: " + error); }
            });
        });
        if (!STARTED.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                if (Runtime.getRuntime().maxMemory() > 192L * 1024 * 1024)
                    throw new AssertionError("Android Java heap exceeds test budget");
                // Reuses exactly the same extraction, receipt, progress and symlink checks as host CI.
                NativeSteamArchiveTest.main(new String[]{new File(getFilesDir(), "steam-legacy.7z").getPath(),
                        new File(getFilesDir(), "staging").getPath()});
                saveResult("PASS native extraction; Java max heap=" + Runtime.getRuntime().maxMemory());
            } catch (Throwable error) { saveResult("FAIL extraction: " + error); }
        }, "archive-probe").start();
    }
    private void saveResult(String text) {
        try { Files.write(new File(getFilesDir(), "result.txt").toPath(), text.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { android.util.Log.e("ArchiveProbe", "Cannot write result", error); }
        android.util.Log.i("ArchiveProbe", text);
    }
}
