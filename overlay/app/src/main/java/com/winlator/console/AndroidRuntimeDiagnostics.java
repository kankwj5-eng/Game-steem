package com.winlator.console;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/** One startup snapshot, without changing system settings or blocking Steam on unknown values. */
public final class AndroidRuntimeDiagnostics {
    private AndroidRuntimeDiagnostics() {}
    public static void record(Context context) {
        String restriction = "no aplica antes de Android 12";
        if (Build.VERSION.SDK_INT == 31) restriction = "no verificable desde la app en Android 12";
        else if (Build.VERSION.SDK_INT >= 32) {
            try {
                String value = Settings.Global.getString(context.getContentResolver(), "settings_enable_monitor_phantom_procs");
                if (value == null || value.trim().isEmpty()) {
                    try {
                        value = (String)Class.forName("android.os.SystemProperties").getMethod("get", String.class)
                                .invoke(null, "persist.sys.fflag.override.settings_enable_monitor_phantom_procs");
                    } catch (Exception ignored) { value = null; }
                }
                restriction = describe(value);
            } catch (Exception ignored) { restriction = "no verificable en este Android"; }
        }
        ConsoleLogStore.info("ANDROID · restricción de procesos hijos: " + restriction);
        ActivityManager manager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            ConsoleLogStore.info("MEMORIA · RAM total " + memory.totalMem / 1048576L + " MiB · disponible "
                    + memory.availMem / 1048576L + " MiB · límite Java " + Runtime.getRuntime().maxMemory() / 1048576L
                    + " MiB · presión del sistema " + (memory.lowMemory ? "sí" : "no"));
        }
    }
    public static String describe(String value) {
        if (value == null || value.trim().isEmpty()) return "valor desconocido; se aplica la configuración del fabricante";
        if ("false".equalsIgnoreCase(value.trim()) || "0".equals(value.trim())) return "desactivada";
        if ("true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim())) return "activada; Android puede cerrar procesos hijos";
        return "valor desconocido; se aplica la configuración del fabricante";
    }
}
