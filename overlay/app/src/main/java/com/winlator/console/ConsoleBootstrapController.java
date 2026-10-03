package com.winlator.console;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.XServerDisplayActivity;
import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.GPUHelper;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ConsoleBootstrapController {
    public static final int REQUEST_RUNTIME = 7202;

    public interface Listener {
        void onStep(BootstrapStep step);
        void onReady(boolean steamInstalled);
        void onDeviceInfo(String gpu, String route);
    }

    private static final String STEAM_INSTALLER_URL = "https://cdn.akamai.steamstatic.com/client/installer/SteamSetup.exe";
    private static final String CONTAINER_MARKER = "droiddeckSteam";
    private static final String CONTAINER_NAME = "DroidDeck Steam";
    private static final long MIN_INSTALLER_BYTES = 500_000L;
    private static final long MIN_FREE_BYTES = 350L * 1024L * 1024L;

    private final AppCompatActivity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, BootstrapStep> steps = new LinkedHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Container steamContainer;
    private boolean prepared;
    private boolean preparing;
    private boolean downloadRunning;
    private boolean installerStarted;
    private boolean pendingAutoLaunch;
    private boolean runtimeActive;

    public ConsoleBootstrapController(AppCompatActivity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        setDefaults();
    }

    private void setDefaults() {
        update("permissions", "Permisos", "Esperando autorización", BootstrapStep.State.WAITING, 0);
        update("system", "Sistema", "Comprobando archivos del motor", BootstrapStep.State.WAITING, 0);
        update("gpu", "Gráficos", "Detectando la mejor ruta para la GPU", BootstrapStep.State.WAITING, 0);
        update("container", "Entorno Steam", "Preparando Wine y Box64", BootstrapStep.State.WAITING, 0);
        update("steam", "Steam", "Comprobando instalación", BootstrapStep.State.WAITING, 0);
        update("launch", "Inicio", "Esperando", BootstrapStep.State.WAITING, 0);
    }

    public void setPermissionsReady(boolean granted, String detail) {
        update("permissions", "Permisos", detail,
                granted ? BootstrapStep.State.DONE : BootstrapStep.State.ERROR,
                granted ? 100 : 0);
        if (granted) ConsoleLogStore.ok("Permisos de almacenamiento listos.");
        else ConsoleLogStore.error("Permisos: " + detail);
    }

    public void prepare() {
        if (preparing || prepared) return;
        preparing = true;
        prepared = true;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        prefs.edit()
                .putBoolean("save_mem_on_run_from_steam", true)
                .putBoolean("use_android_clipboard_on_wine", true)
                .putBoolean("enable_wine_debug", true)
                .putString("wine_debug_channels", "warn,err,fixme")
                .putInt("box64_logs", 1)
                .apply();

        update("system", "Sistema", "Verificando RootFS", BootstrapStep.State.RUNNING, 5);
        RootFS root = RootFS.find(activity);
        if (root.isValid() && root.getVersion() >= RootFSInstaller.LATEST_VERSION) {
            doneSystem();
            prepareContainer();
            return;
        }

        ConsoleLogStore.info("RootFS ausente o desactualizado; iniciando instalación interna.");
        RootFSInstaller.install(activity, new RootFSInstaller.InstallProgressListener() {
            @Override
            public void onProgress(int progress) {
                update("system", "Sistema", "Instalando archivos del motor…", BootstrapStep.State.RUNNING, progress);
            }

            @Override
            public void onCompleted(boolean success) {
                if (!success) {
                    preparing = false;
                    fail("system", "No se pudieron instalar los archivos del motor");
                    return;
                }
                doneSystem();
                prepareContainer();
            }
        });
    }

    public void refreshAfterResume() {
        if (!prepared || runtimeActive) return;
        RootFS root = RootFS.find(activity);
        if (!root.isValid() || root.getVersion() < RootFSInstaller.LATEST_VERSION) return;
        if (steamContainer == null) return;

        File steam = findSteamExecutable(steamContainer);
        if (steam != null) {
            update("steam", "Steam", "Cliente instalado", BootstrapStep.State.DONE, 100);
            listener.onReady(true);
            if (pendingAutoLaunch) {
                pendingAutoLaunch = false;
                installerStarted = false;
                main.postDelayed(this::startSteam, 450);
            }
        }
        else if (installerStarted) {
            installerStarted = false;
            pendingAutoLaunch = false;
            fail("steam", "El instalador terminó, pero Steam no apareció en el prefijo");
        }
    }

    private void doneSystem() {
        update("system", "Sistema", "Motor listo", BootstrapStep.State.DONE, 100);
        ConsoleLogStore.ok("RootFS listo.");
        String renderer = GPUHelper.glGetRenderer(activity);
        String route = describeGpuRoute();
        update("gpu", "Gráficos", route, BootstrapStep.State.DONE, 100);
        listener.onDeviceInfo(renderer == null || renderer.isEmpty() ? "GPU no identificada" : renderer, route);
    }

    private String describeGpuRoute() {
        String route = GraphicsDrivers.getDefaultDriver(activity);
        ConsoleLogStore.info("Ruta gráfica automática: " + route);
        return route.startsWith(GraphicsDrivers.TURNIP) ? "Turnip + Gladio" : "Vortek + Gladio";
    }

    private void prepareContainer() {
        update("container", "Entorno Steam", "Buscando entorno existente", BootstrapStep.State.RUNNING, 15);
        ContainerManager manager = new ContainerManager(activity);
        for (Container candidate : manager.getContainers()) {
            if ("t".equals(candidate.getExtra(CONTAINER_MARKER))) {
                steamContainer = candidate;
                update("container", "Entorno Steam", "Wine + Box64 listos", BootstrapStep.State.DONE, 100);
                preparing = false;
                checkSteam(true);
                return;
            }
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", "1280x720");
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("cpuList", Container.getFallbackCPUList());
            data.put("cpuListWoW64", Container.getFallbackCPUList());
            data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(activity));
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("dxwrapperConfig", "");
            data.put("graphicsDriverConfig", "");
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("audioDriverConfig", "");
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", Container.DEFAULT_DRIVES);
            data.put("hudMode", 0);
            data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);
            data.put("box64Preset", Box64Preset.PERFORMANCE);
            data.put("desktopTheme", WineThemeManager.Theme.DARK + "," + WineThemeManager.BackgroundType.COLOR + ",#080B14");

            update("container", "Entorno Steam", "Creando prefijo Wine", BootstrapStep.State.RUNNING, 45);
            manager.createContainerAsync(data, container -> {
                preparing = false;
                if (container == null) {
                    fail("container", "No se pudo crear el entorno Wine/Box64");
                    return;
                }
                container.putExtra(CONTAINER_MARKER, "t");
                container.saveData();
                steamContainer = container;
                update("container", "Entorno Steam", "Wine + Box64 listos", BootstrapStep.State.DONE, 100);
                ConsoleLogStore.ok("Contenedor Steam creado: id=" + container.id);
                checkSteam(true);
            });
        }
        catch (Exception e) {
            preparing = false;
            fail("container", e.getMessage());
        }
    }

    private void checkSteam(boolean autoInstall) {
        update("steam", "Steam", "Comprobando cliente", BootstrapStep.State.RUNNING, 20);
        File steam = findSteamExecutable(steamContainer);
        if (steam != null) {
            update("steam", "Steam", "Cliente listo", BootstrapStep.State.DONE, 100);
            listener.onReady(true);
        }
        else {
            update("steam", "Steam", "Instalación automática necesaria", BootstrapStep.State.WAITING, 0);
            listener.onReady(false);
            if (autoInstall) downloadAndRunInstaller();
        }
    }

    public void startSteam() {
        if (downloadRunning) return;
        if (steamContainer == null) {
            prepare();
            return;
        }
        File steam = findSteamExecutable(steamContainer);
        if (steam != null) {
            update("launch", "Inicio", "Abriendo Steam", BootstrapStep.State.RUNNING, 70);
            launchWindowsExecutable(steam, null, "steam_client");
            main.postDelayed(() -> update("launch", "Inicio", "Steam ejecutándose", BootstrapStep.State.DONE, 100), 900);
        }
        else downloadAndRunInstaller();
    }

    public void retry() {
        downloadRunning = false;
        installerStarted = false;
        pendingAutoLaunch = false;
        runtimeActive = false;
        if (steamContainer == null) {
            prepared = false;
            preparing = false;
            prepare();
        }
        else checkSteam(true);
    }

    private void downloadAndRunInstaller() {
        if (downloadRunning || installerStarted) return;
        if (!hasNetwork()) {
            fail("steam", "Sin conexión a Internet");
            return;
        }
        if (!hasEnoughSpace()) {
            fail("steam", "No hay espacio libre suficiente para preparar Steam");
            return;
        }

        downloadRunning = true;
        update("steam", "Steam", "Descargando instalador oficial", BootstrapStep.State.RUNNING, 2);
        ConsoleLogStore.info("Descargando SteamSetup.exe desde el CDN oficial de Steam.");
        io.execute(() -> {
            File dir = new File(steamContainer.getRootDir(), ".wine/drive_c/DroidDeck");
            if (!dir.exists() && !dir.mkdirs()) {
                main.post(() -> {
                    downloadRunning = false;
                    fail("steam", "No se pudo crear C:\\DroidDeck dentro del prefijo Wine");
                });
                return;
            }
            File dst = new File(dir, "SteamSetup.exe");
            File part = new File(dir, "SteamSetup.exe.part");
            ConsoleLogStore.info("Destino real Wine: C:\\DroidDeck\\SteamSetup.exe");
            ConsoleLogStore.info("Destino Android: " + dst.getAbsolutePath());

            if (isValidInstaller(dst)) {
                main.post(() -> runInstaller(dst));
                return;
            }

            Exception lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    downloadOnce(part, attempt);
                    if (!isValidInstaller(part)) throw new IllegalStateException("El archivo descargado no parece ser un ejecutable válido");
                    if (dst.exists()) dst.delete();
                    if (!part.renameTo(dst)) throw new IllegalStateException("No se pudo finalizar el archivo descargado");
                    main.post(() -> runInstaller(dst));
                    return;
                }
                catch (Exception e) {
                    lastError = e;
                    ConsoleLogStore.warn("Descarga Steam intento " + attempt + "/3: " + e.getMessage());
                    try { Thread.sleep(900L * attempt); } catch (InterruptedException ignored) {}
                }
            }
            final String message = lastError != null ? lastError.getMessage() : "Error desconocido";
            main.post(() -> {
                downloadRunning = false;
                fail("steam", "Descarga fallida: " + message);
            });
        });
    }

    private void downloadOnce(File part, int attempt) throws Exception {
        long existing = part.isFile() ? part.length() : 0L;
        HttpURLConnection connection = (HttpURLConnection)new URL(STEAM_INSTALLER_URL).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DroidDeck/0.4 Android");
        if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
        connection.connect();

        int code = connection.getResponseCode();
        boolean resumed = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL;
        if (code / 100 != 2) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        if (!resumed) existing = 0L;

        long bodyLength = connection.getContentLengthLong();
        long expected = bodyLength > 0 ? existing + bodyLength : -1L;
        ConsoleLogStore.info("Steam HTTP " + code
                + (resumed ? " · reanudando desde " + formatBytes(existing) : " · descarga nueva")
                + (expected > 0 ? " · total " + formatBytes(expected) : ""));

        try (InputStream raw = new BufferedInputStream(connection.getInputStream());
             FileOutputStream out = new FileOutputStream(part, resumed)) {
            byte[] buffer = new byte[128 * 1024];
            long total = existing;
            long sampleBytes = total;
            long sampleTime = System.currentTimeMillis();
            long lastUi = 0L;
            int lastLoggedPct = -5;
            int n;

            while ((n = raw.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                total += n;
                long now = System.currentTimeMillis();

                if (now - lastUi >= 200L) {
                    long elapsed = Math.max(1L, now - sampleTime);
                    long delta = Math.max(0L, total - sampleBytes);
                    double speed = (delta * 1000.0) / elapsed;
                    sampleBytes = total;
                    sampleTime = now;
                    lastUi = now;

                    final int pct = expected > 0 ? Math.min(100, (int)((100L * total) / expected)) : 0;
                    final long copied = total;
                    final long target = expected;
                    final double bytesPerSecond = speed;
                    final String detail = target > 0
                            ? pct + "% · " + formatBytes(copied) + " de " + formatBytes(target) + " · " + formatSpeed(bytesPerSecond)
                            : formatBytes(copied) + " · " + formatSpeed(bytesPerSecond);

                    main.post(() -> update("steam", "Steam", detail, BootstrapStep.State.RUNNING, pct));

                    if (pct >= lastLoggedPct + 5) {
                        lastLoggedPct = pct;
                        ConsoleLogStore.info("Steam download · " + detail);
                    }
                }
            }
            out.getFD().sync();
        }
        finally {
            connection.disconnect();
        }

        long finalSize = part.length();
        if (expected > 0 && finalSize != expected) {
            throw new IllegalStateException("Tamaño incompleto: " + formatBytes(finalSize) + " de " + formatBytes(expected));
        }
        ConsoleLogStore.ok("SteamSetup.exe descargado completo · " + formatBytes(finalSize));
    }

    private void runInstaller(File installer) {
        downloadRunning = false;
        if (!isValidInstaller(installer)) {
            fail("steam", "SteamSetup.exe no pasó la validación antes de ejecutarse");
            return;
        }

        String dosPath = WineUtils.unixToDOSPath(installer.getAbsolutePath(), steamContainer);
        if (dosPath == null || dosPath.isEmpty() || !dosPath.toUpperCase(java.util.Locale.US).startsWith("C:")) {
            fail("steam", "El instalador no quedó en una ruta válida de Wine: " + dosPath);
            return;
        }

        installerStarted = true;
        pendingAutoLaunch = true;
        update("steam", "Steam", "Instalando automáticamente · C:\\DroidDeck\\SteamSetup.exe", BootstrapStep.State.RUNNING, 98);
        ConsoleLogStore.ok("Instalador listo · " + formatBytes(installer.length()) + " · " + dosPath);
        ConsoleLogStore.info("Ejecutando " + dosPath + " /S dentro de Wine.");
        launchWindowsExecutable(installer, "/S", "steam_install");
    }

    private void launchWindowsExecutable(File executable, String args, String purpose) {
        String dosPath = WineUtils.unixToDOSPath(executable.getAbsolutePath(), steamContainer);
        if (dosPath == null || dosPath.isEmpty() || !dosPath.contains(":")) {
            fail("steam", "Wine no pudo mapear el ejecutable: " + executable.getAbsolutePath());
            return;
        }
        ConsoleLogStore.info("Ruta Wine resuelta: " + dosPath);
        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.putExtra("container_id", steamContainer.id);
        intent.putExtra("exec_path", executable.getAbsolutePath());
        if (args != null && !args.trim().isEmpty()) intent.putExtra("exec_args", args.trim());
        intent.putExtra("droiddeck_console", true);
        intent.putExtra("droiddeck_purpose", purpose);
        runtimeActive = true;
        activity.startActivityForResult(intent, REQUEST_RUNTIME);
        ConsoleLogStore.info("Wine launch [" + purpose + "]: " + executable.getAbsolutePath() + (args == null ? "" : " " + args));
    }

    public void handleRuntimeResult(int resultCode, Intent data) {
        runtimeActive = false;
        if (resultCode != Activity.RESULT_OK || data == null) {
            ConsoleLogStore.warn("El runtime volvió sin código de salida utilizable.");
            refreshAfterResume();
            return;
        }
        int status = data.getIntExtra("droiddeck_runtime_exit_status", Integer.MIN_VALUE);
        String purpose = data.getStringExtra("droiddeck_runtime_purpose");
        ConsoleLogStore.info("Runtime finalizado · propósito=" + purpose + " · código=" + status);

        if ("steam_install".equals(purpose)) {
            installerStarted = false;
            if (status != 0) {
                pendingAutoLaunch = false;
                fail("steam", "SteamSetup terminó con código " + status + ". Revisa la consola.");
                return;
            }
            waitForSteamAfterInstall(0);
            return;
        }

        if ("steam_client".equals(purpose)) {
            if (status == 0) {
                update("launch", "Inicio", "Steam cerrado por el usuario", BootstrapStep.State.DONE, 100);
            }
            else {
                fail("launch", "Steam terminó con código " + status + ". Revisa la consola.");
            }
            return;
        }

        refreshAfterResume();
    }

    private void waitForSteamAfterInstall(int attempt) {
        File steam = findSteamExecutable(steamContainer);
        if (steam != null) {
            installerStarted = false;
            update("steam", "Steam", "Instalación completada", BootstrapStep.State.DONE, 100);
            ConsoleLogStore.ok("steam.exe encontrado: " + steam.getAbsolutePath());
            listener.onReady(true);
            if (pendingAutoLaunch) {
                pendingAutoLaunch = false;
                main.postDelayed(this::startSteam, 350);
            }
            return;
        }

        if (attempt >= 30) {
            installerStarted = false;
            pendingAutoLaunch = false;
            logSteamDirectoryState();
            fail("steam", "SteamSetup terminó, pero steam.exe no apareció después de 15 s");
            return;
        }

        int seconds = attempt / 2;
        int progress = Math.min(99, 96 + (attempt / 10));
        update("steam", "Steam",
                "Finalizando instalación… " + seconds + " s · buscando steam.exe",
                BootstrapStep.State.RUNNING, progress);
        if (attempt % 4 == 0) {
            ConsoleLogStore.info("Verificando steam.exe… intento " + (attempt + 1) + "/31");
        }
        main.postDelayed(() -> waitForSteamAfterInstall(attempt + 1), 500);
    }

    private void logSteamDirectoryState() {
        if (steamContainer == null) return;
        File programFiles = new File(steamContainer.getRootDir(), ".wine/drive_c/Program Files (x86)");
        File programFiles64 = new File(steamContainer.getRootDir(), ".wine/drive_c/Program Files");
        ConsoleLogStore.warn("Inspección de C:\\Program Files (x86): " + listNames(programFiles));
        ConsoleLogStore.warn("Inspección de C:\\Program Files: " + listNames(programFiles64));
    }

    private String listNames(File dir) {
        if (dir == null || !dir.isDirectory()) return "[no existe]";
        String[] names = dir.list();
        if (names == null || names.length == 0) return "[vacío]";
        java.util.Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.length && i < 40; i++) {
            if (i > 0) out.append(", ");
            out.append(names[i]);
        }
        return out.toString();
    }

    private File findSteamExecutable(Container container) {
        if (container == null) return null;
        String[] paths = {
                ".wine/drive_c/Program Files (x86)/Steam/steam.exe",
                ".wine/drive_c/Program Files/Steam/steam.exe"
        };
        for (String path : paths) {
            File f = new File(container.getRootDir(), path);
            if (f.isFile()) return f;
        }
        return null;
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager)activity.getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        catch (Exception e) {
            return true;
        }
    }

    private boolean hasEnoughSpace() {
        try {
            StatFs stat = new StatFs(activity.getFilesDir().getAbsolutePath());
            return stat.getAvailableBytes() >= MIN_FREE_BYTES;
        }
        catch (Exception e) {
            return true;
        }
    }

    private boolean isValidInstaller(File file) {
        if (file == null || !file.isFile() || file.length() < MIN_INSTALLER_BYTES) return false;
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read() == 'M' && in.read() == 'Z';
        }
        catch (Exception e) {
            return false;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1024.0 * 1024.0) {
            return String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0);
        }
        if (bytesPerSecond >= 1024.0) {
            return String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.0f B/s", bytesPerSecond);
    }

    private void fail(String id, String message) {
        String safe = message == null || message.isEmpty() ? "Error desconocido" : message;
        update(id, titleFor(id), safe, BootstrapStep.State.ERROR, 0);
        ConsoleLogStore.error(id + ": " + safe);
    }

    private String titleFor(String id) {
        BootstrapStep step = steps.get(id);
        return step != null ? step.title : id;
    }

    private void update(String id, String title, String detail, BootstrapStep.State state, int progress) {
        BootstrapStep step = new BootstrapStep(id, title, detail, state, progress);
        steps.put(id, step);
        if (listener != null) listener.onStep(step);
    }
}
