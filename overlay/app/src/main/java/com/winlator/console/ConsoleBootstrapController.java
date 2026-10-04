package com.winlator.console;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConsoleBootstrapController {
    public static final int REQUEST_RUNTIME = 7202;

    public interface Listener {
        void onStep(BootstrapStep step);
        void onReady(boolean steamInstalled);
        void onDeviceInfo(String gpu, String route);
    }

    private static final String CONTAINER_MARKER = "droiddeckSteam";
    private static final String CONTAINER_NAME = "DroidDeck Steam";
    private static final long MIN_FREE_BYTES = 1400L * 1024L * 1024L;

    private final AppCompatActivity activity;
    private final Listener listener;
    private final Map<String, BootstrapStep> steps = new LinkedHashMap<>();

    private final SteamLegacyInstaller.Listener installerListener = this::onInstallerState;

    private Container steamContainer;
    private boolean prepared;
    private boolean preparing;
    private boolean runtimeActive;
    private boolean installRequested;

    public ConsoleBootstrapController(AppCompatActivity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        setDefaults();
        SteamLegacyInstaller.addListener(installerListener);
    }

    private void setDefaults() {
        update("permissions", "Permisos", "Esperando autorización", BootstrapStep.State.WAITING, 0);
        update("system", "Sistema", "Comprobando archivos del motor", BootstrapStep.State.WAITING, 0);
        update("gpu", "Gráficos", "Detectando la mejor ruta para la GPU", BootstrapStep.State.WAITING, 0);
        update("container", "Entorno Steam", "Preparando Wine y Box64", BootstrapStep.State.WAITING, 0);
        update("steam", "Steam", "Comprobando instalación", BootstrapStep.State.WAITING, 0);
        update("launch", "Inicio", "Esperando", BootstrapStep.State.WAITING, 0);
    }

    public void destroy() {
        SteamLegacyInstaller.removeListener(installerListener);
    }

    public void setPermissionsReady(boolean granted, String detail) {
        update(
                "permissions",
                "Permisos",
                detail,
                granted ? BootstrapStep.State.DONE : BootstrapStep.State.ERROR,
                granted ? 100 : 0
        );
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
                .putBoolean("enable_background_protection", true)
                .putBoolean("enable_background_wakelock", true)
                .apply();

        ConsoleLogStore.info("BOOT · DroidDeck M17 · Winlator 11.2 · instalación Steam Legacy nativa Android");
        update("system", "Sistema", "Verificando RootFS de Winlator 11.2", BootstrapStep.State.RUNNING, 5);

        RootFS root = RootFS.find(activity);
        if (root.isValid() && root.getVersion() >= RootFSInstaller.LATEST_VERSION) {
            doneSystem();
            prepareContainer();
            return;
        }

        ConsoleLogStore.info("ROOTFS · ausente o desactualizado; instalando recursos internos.");
        RootFSInstaller.install(activity, new RootFSInstaller.InstallProgressListener() {
            @Override
            public void onProgress(int progress) {
                update(
                        "system",
                        "Sistema",
                        "Instalando RootFS · " + progress + "%",
                        BootstrapStep.State.RUNNING,
                        progress
                );
                if (progress % 10 == 0) ConsoleLogStore.info("ROOTFS · " + progress + "%");
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
        if (!prepared || runtimeActive || steamContainer == null) return;

        File steam = findSteamExecutable(steamContainer);
        if (steam != null) {
            markSteamReady(steam);
            return;
        }

        SteamLegacyInstaller.State state = SteamLegacyInstaller.getState();
        if (SteamLegacyInstaller.isRunning()) {
            onInstallerState(state);
        }
        else if (state.phase == SteamLegacyInstaller.Phase.ERROR) {
            fail("steam", state.detail);
        }
    }

    public void startSteam() {
        if (runtimeActive || SteamLegacyInstaller.isRunning()) return;

        if (steamContainer == null) {
            prepared = false;
            preparing = false;
            prepare();
            return;
        }

        File steam = findSteamExecutable(steamContainer);
        if (steam == null) {
            installSteamLegacy();
            return;
        }

        launchSteam(steam);
    }

    public void retry() {
        runtimeActive = false;
        installRequested = false;

        if (steamContainer == null) {
            prepared = false;
            preparing = false;
            prepare();
            return;
        }

        File steam = findSteamExecutable(steamContainer);
        if (steam != null) {
            markSteamReady(steam);
            return;
        }

        installSteamLegacy();
    }

    private void doneSystem() {
        update("system", "Sistema", "RootFS y motor listos", BootstrapStep.State.DONE, 100);
        ConsoleLogStore.ok("ROOTFS · listo.");

        String renderer = GPUHelper.glGetRenderer(activity);
        String route = describeGpuRoute();
        update("gpu", "Gráficos", route, BootstrapStep.State.DONE, 100);
        listener.onDeviceInfo(
                renderer == null || renderer.isEmpty() ? "GPU no identificada" : renderer,
                route
        );
    }

    private String describeGpuRoute() {
        String route = GraphicsDrivers.getDefaultDriver(activity);
        String description = route.startsWith(GraphicsDrivers.TURNIP)
                ? "Turnip + Gladio"
                : "Vortek + Gladio";
        ConsoleLogStore.info("GPU · ruta automática: " + description + " (" + route + ")");
        return description;
    }

    private void prepareContainer() {
        update(
                "container",
                "Entorno Steam",
                "Buscando prefijo DroidDeck",
                BootstrapStep.State.RUNNING,
                15
        );

        ContainerManager manager = new ContainerManager(activity);
        for (Container candidate : manager.getContainers()) {
            if ("t".equals(candidate.getExtra(CONTAINER_MARKER))
                    || CONTAINER_NAME.equals(candidate.getName())) {
                steamContainer = candidate;
                if (!"t".equals(candidate.getExtra(CONTAINER_MARKER))) {
                    candidate.putExtra(CONTAINER_MARKER, "t");
                    candidate.saveData();
                }

                preparing = false;
                update("container", "Entorno Steam", "Wine + Box64 listos", BootstrapStep.State.DONE, 100);
                ConsoleLogStore.ok("CONTENEDOR · reutilizando id=" + candidate.id + " · " + candidate.getRootDir());
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
            data.put(
                    "desktopTheme",
                    WineThemeManager.Theme.DARK + "," + WineThemeManager.BackgroundType.COLOR + ",#080B14"
            );

            update(
                    "container",
                    "Entorno Steam",
                    "Creando prefijo Wine limpio",
                    BootstrapStep.State.RUNNING,
                    45
            );
            ConsoleLogStore.info("CONTENEDOR · creando prefijo Wine para Steam.");

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
                ConsoleLogStore.ok("CONTENEDOR · creado id=" + container.id + " · " + container.getRootDir());
                checkSteam(true);
            });
        }
        catch (Exception error) {
            preparing = false;
            fail("container", error.getMessage());
        }
    }

    private void checkSteam(boolean autoInstall) {
        update("steam", "Steam", "Buscando steam.exe", BootstrapStep.State.RUNNING, 5);

        File steam = findSteamExecutable(steamContainer);
        if (steam != null) {
            markSteamReady(steam);
            return;
        }

        listener.onReady(false);
        update(
                "steam",
                "Steam",
                "Steam Legacy de Winlator 11.2 todavía no está instalado",
                BootstrapStep.State.WAITING,
                0
        );
        ConsoleLogStore.info("STEAM · no existe C:\\Program Files (x86)\\Steam\\steam.exe");

        if (autoInstall) installSteamLegacy();
    }

    private void installSteamLegacy() {
        if (steamContainer == null || SteamLegacyInstaller.isRunning()) return;

        if (!hasNetwork()) {
            fail("steam", "Sin conexión a Internet para descargar Steam Legacy");
            return;
        }
        if (!hasEnoughSpace()) {
            fail("steam", "Se necesitan al menos 1.4 GB libres para Steam Legacy (descarga + extracción + margen)");
            return;
        }

        installRequested = true;
        listener.onReady(false);
        update(
                "steam",
                "Steam",
                "Preparando paquete Steam Legacy de Winlator",
                BootstrapStep.State.RUNNING,
                1
        );

        ConsoleLogStore.info("STEAM · iniciando instalación directa del paquete oficial de Winlator Addons.");
        boolean started = SteamLegacyInstaller.start(activity, steamContainer);
        if (!started && !SteamLegacyInstaller.isRunning()) {
            fail("steam", "No se pudo iniciar el instalador de Steam Legacy");
        }
    }

    private void onInstallerState(SteamLegacyInstaller.State state) {
        if (state == null || steamContainer == null) return;

        switch (state.phase) {
            case READY: {
                File steam = findSteamExecutable(steamContainer);
                if (steam != null) {
                    installRequested = false;
                    markSteamReady(steam);
                }
                else {
                    fail("steam", "El instalador informó éxito, pero steam.exe no existe");
                }
                break;
            }
            case ERROR:
                installRequested = false;
                fail("steam", state.detail);
                break;
            case IDLE:
                break;
            default:
                update(
                        "steam",
                        "Steam",
                        state.detail,
                        BootstrapStep.State.RUNNING,
                        state.progress
                );
                break;
        }
    }

    private void markSteamReady(File steam) {
        if (steam == null || !steam.isFile()) return;

        update(
                "steam",
                "Steam",
                "Cliente verificado · " + humanPath(steam),
                BootstrapStep.State.DONE,
                100
        );
        update(
                "launch",
                "Inicio",
                "Todo listo · inicia Steam cuando quieras",
                BootstrapStep.State.WAITING,
                0
        );
        ConsoleLogStore.ok("STEAM · cliente verificado: " + steam.getAbsolutePath());
        listener.onReady(true);
    }

    private void launchSteam(File steam) {
        if (steam == null || !steam.isFile()) {
            fail("launch", "steam.exe desapareció antes de arrancar");
            return;
        }

        update(
                "launch",
                "Inicio",
                "Arrancando Steam con el perfil nativo de Winlator 11.2",
                BootstrapStep.State.RUNNING,
                10
        );

        ConsoleLogStore.info("LAUNCH · steam.exe: " + steam.getAbsolutePath());
        ConsoleLogStore.info("LAUNCH · Box64 aplicará el bloque [steam.exe] de default.box64rc.");

        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.putExtra("container_id", steamContainer.id);
        intent.putExtra("exec_path", steam.getAbsolutePath());
        intent.putExtra("droiddeck_console", true);
        intent.putExtra("droiddeck_purpose", "steam_client");

        runtimeActive = true;
        activity.startActivityForResult(intent, REQUEST_RUNTIME);
    }

    public void handleRuntimeResult(int resultCode, Intent data) {
        runtimeActive = false;

        if (resultCode != Activity.RESULT_OK || data == null) {
            ConsoleLogStore.warn("RUNTIME · Steam volvió sin código de salida utilizable.");
            File steam = findSteamExecutable(steamContainer);
            if (steam != null) {
                update("launch", "Inicio", "Steam se cerró o volvió al launcher", BootstrapStep.State.WAITING, 0);
                listener.onReady(true);
            }
            return;
        }

        int status = data.getIntExtra("droiddeck_runtime_exit_status", Integer.MIN_VALUE);
        String purpose = data.getStringExtra("droiddeck_runtime_purpose");
        ConsoleLogStore.info("RUNTIME · propósito=" + purpose + " · código=" + status);

        if ("steam_client".equals(purpose)) {
            if (status == 0) {
                update("launch", "Inicio", "Steam cerrado", BootstrapStep.State.DONE, 100);
            }
            else {
                fail("launch", "Steam terminó con código " + status + " · revisa la consola real");
            }
        }
    }

    private File findSteamExecutable(Container container) {
        if (container == null) return null;

        String[] paths = {
                ".wine/drive_c/Program Files (x86)/Steam/steam.exe",
                ".wine/drive_c/Steam/steam.exe",
                ".wine/drive_c/Program Files/Steam/steam.exe"
        };

        for (String path : paths) {
            File file = new File(container.getRootDir(), path);
            if (file.isFile() && file.length() > 256 * 1024L) return file;
        }
        return null;
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager)activity.getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        catch (Exception ignored) {
            return true;
        }
    }

    private boolean hasEnoughSpace() {
        try {
            StatFs stat = new StatFs(activity.getFilesDir().getAbsolutePath());
            long available = stat.getAvailableBytes();
            ConsoleLogStore.info("DISCO · libre " + formatBytes(available));
            return available >= MIN_FREE_BYTES;
        }
        catch (Exception ignored) {
            return true;
        }
    }

    private String humanPath(File file) {
        if (steamContainer == null || file == null) return "";
        String base = new File(steamContainer.getRootDir(), ".wine/drive_c").getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.startsWith(base)) {
            return "C:" + path.substring(base.length()).replace('/', '\\');
        }
        return file.getName();
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private void fail(String id, String message) {
        String safe = message == null || message.trim().isEmpty() ? "Error desconocido" : message.trim();
        update(id, titleFor(id), safe, BootstrapStep.State.ERROR, 0);
        ConsoleLogStore.error(id.toUpperCase(java.util.Locale.US) + " · " + safe);
    }

    private String titleFor(String id) {
        BootstrapStep step = steps.get(id);
        return step != null ? step.title : id;
    }

    private void update(String id, String title, String detail, BootstrapStep.State state, int progress) {
        BootstrapStep step = new BootstrapStep(
                id,
                title,
                detail,
                state,
                Math.max(0, Math.min(100, progress))
        );
        steps.put(id, step);
        if (listener != null) listener.onStep(step);
    }
}
