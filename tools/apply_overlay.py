#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys
import re

EXPECTED_SHA = "3981d86efa4f333b2a34a7da8b6521476cd8c8b9"

if len(sys.argv) != 2:
    raise SystemExit("uso: apply_overlay.py /ruta/a/winlator-app")

src = Path(sys.argv[1]).resolve()
this = Path(__file__).resolve().parents[1]
overlay = this / "overlay"
manifest = src / "app/src/main/AndroidManifest.xml"
rootfs = src / "app/src/main/java/com/winlator/xenvironment/RootFSInstaller.java"
build_gradle = src / "app/build.gradle"
file_utils = src / "app/src/main/java/com/winlator/core/FileUtils.java"
app_utils = src / "app/src/main/java/com/winlator/core/AppUtils.java"
winlator_h = src / "app/src/main/cpp/winlator/include/winlator.h"
vortek_h = src / "app/src/main/cpp/vortekrenderer/include/vortek.h"
gladio_h = src / "app/src/main/cpp/gladiorenderer/include/gladio.h"
xserver = src / "app/src/main/java/com/winlator/XServerDisplayActivity.java"
foreground_service = src / "app/src/main/java/com/winlator/services/ForegroundService.java"
notification_utils = src / "app/src/main/java/com/winlator/services/NotificationUtils.java"

for required in (manifest, rootfs, build_gradle, file_utils, app_utils, winlator_h, vortek_h, gladio_h, xserver, foreground_service, notification_utils):
    if not required.exists():
        raise SystemExit(f"árbol Winlator inválido; falta {required}")

for p in overlay.rglob("*"):
    if p.is_file():
        rel = p.relative_to(overlay)
        dst = src / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, dst)

text = manifest.read_text(encoding="utf-8")
if "com.winlator.ConsoleLauncherActivity" not in text:
    main_pat = re.compile(r'(<activity android:name="com\.winlator\.MainActivity".*?</activity>)', re.S)
    m = main_pat.search(text)
    if not m:
        raise SystemExit("no se encontró MainActivity en manifest")
    main_block = m.group(1)
    intent_pat = re.compile(r'\s*<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"/>\s*<category android:name="android\.intent\.category\.LAUNCHER"/>\s*</intent-filter>', re.S)
    clean_main, n = intent_pat.subn("", main_block, count=1)
    if n != 1:
        raise SystemExit("no se encontró intent-filter LAUNCHER esperado")
    console_block = """
        <activity android:name="com.winlator.ConsoleLauncherActivity"
            android:theme="@style/AppThemeFullscreenDark"
            android:exported="true"
            android:screenOrientation="sensorLandscape"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
"""
    text = text.replace(main_block, clean_main + console_block)

text = text.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_droiddeck_logo"\n        android:roundIcon="@drawable/ic_droiddeck_logo"')
text = text.replace('android:authorities="com.winlator.FileProvider"', 'android:authorities="com.droiddeck.console.FileProvider"')
text = text.replace('android:label="@string/app_name">', 'android:label="DroidDeck">')
text = text.replace('android:name="com.winlator.ConsoleLauncherActivity"\n            android:theme=', 'android:name="com.winlator.ConsoleLauncherActivity"\n            android:label="DroidDeck"\n            android:icon="@drawable/ic_droiddeck_logo"\n            android:theme=')
if "android:requestLegacyExternalStorage=" not in text:
    if 'android:label="DroidDeck">' in text:
        text = text.replace('android:label="DroidDeck">', 'android:label="DroidDeck"\n        android:requestLegacyExternalStorage="true">', 1)
    else:
        text = text.replace('android:label="@string/app_name">', 'android:label="@string/app_name"\n        android:requestLegacyExternalStorage="true">', 1)
manifest.write_text(text, encoding="utf-8")

btext = build_gradle.read_text(encoding="utf-8")
btext = btext.replace("applicationId 'com.winlator'", "applicationId 'com.droiddeck.console'")
btext = btext.replace('versionName "11.2"', 'versionName "0.7.0-m7"')
build_gradle.write_text(btext, encoding="utf-8")

for strings in (src / "app/src/main/res").glob("values*/strings.xml"):
    stext = strings.read_text(encoding="utf-8")
    stext = re.sub(r'<string name="app_name">.*?</string>', '<string name="app_name">DroidDeck</string>', stext, count=1)
    strings.write_text(stext, encoding="utf-8")

ftext = file_utils.read_text(encoding="utf-8")
ftext = ftext.replace('"com.winlator.FileProvider"', '"com.droiddeck.console.FileProvider"')
file_utils.write_text(ftext, encoding="utf-8")

# Hardcoded upstream package paths must follow DroidDeck's applicationId.
atext = app_utils.read_text(encoding="utf-8")
atext = atext.replace("/data/data/com.winlator/storage", "/data/data/com.droiddeck.console/storage")
app_utils.write_text(atext, encoding="utf-8")

for native_file in (winlator_h, vortek_h, gladio_h):
    ntext = native_file.read_text(encoding="utf-8")
    ntext = ntext.replace("/data/data/com.winlator/", "/data/data/com.droiddeck.console/")
    native_file.write_text(ntext, encoding="utf-8")

rtext = rootfs.read_text(encoding="utf-8")
rtext = rtext.replace(
    "public static void install(final MainActivity activity)",
    "public static void install(final AppCompatActivity activity, final InstallProgressListener listener)"
)
rtext = rtext.replace(
    "public static void installIfNeeded(final MainActivity activity)",
    "public static void installIfNeeded(final AppCompatActivity activity)"
)
if "interface InstallProgressListener" not in rtext:
    rtext = rtext.replace(
        "public abstract class RootFSInstaller {",
        "public abstract class RootFSInstaller {\n"
        "    public interface InstallProgressListener {\n"
        "        void onProgress(int progress);\n"
        "        void onCompleted(boolean success);\n"
        "    }"
    )
    rtext = rtext.replace(
        "        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);\n"
        "        dialog.show(R.string.installing_system_files);",
        "        final DownloadProgressDialog dialog = listener == null ? new DownloadProgressDialog(activity) : null;\n"
        "        if (dialog != null) dialog.show(R.string.installing_system_files);"
    )
    rtext = rtext.replace(
        "activity.runOnUiThread(() -> dialog.setProgress(progress));",
        "activity.runOnUiThread(() -> {\n"
        "                        if (listener != null) listener.onProgress(progress);\n"
        "                        else if (dialog != null) dialog.setProgress(progress);\n"
        "                    });"
    )
    rtext = rtext.replace(
        "dialog.closeOnUiThread();",
        "if (listener != null) activity.runOnUiThread(() -> listener.onCompleted(success));\n"
        "            else if (dialog != null) dialog.closeOnUiThread();"
    )
    anchor = "    public static void installIfNeeded(final AppCompatActivity activity) {"
    overload = (
        "    public static void install(final AppCompatActivity activity) {\n"
        "        install(activity, null);\n"
        "    }\n\n"
    )
    if anchor not in rtext:
        raise SystemExit("firma installIfNeeded no encontrada en RootFSInstaller")
    rtext = rtext.replace(anchor, overload + anchor)
rootfs.write_text(rtext, encoding="utf-8")

xtext = xserver.read_text(encoding="utf-8")
if "import com.winlator.console.ConsoleLogStore;" not in xtext:
    xtext = xtext.replace(
        "import com.winlator.contentdialog.WineD3DConfigDialog;",
        "import com.winlator.contentdialog.WineD3DConfigDialog;\nimport com.winlator.console.ConsoleLogStore;\nimport com.winlator.console.RuntimeConsoleOverlay;\nimport com.winlator.console.SteamRuntimeWatchdog;"
    )
if "private boolean droidDeckConsoleMode;" not in xtext:
    xtext = xtext.replace(
        "private String screenEffectProfile;",
        "private String screenEffectProfile;\n    private boolean droidDeckConsoleMode;\n    private RuntimeConsoleOverlay droidDeckRuntimeOverlay;\n    private SteamRuntimeWatchdog droidDeckSteamWatchdog;"
    )
if 'droidDeckConsoleMode = getIntent().getBooleanExtra("droiddeck_console", false);' not in xtext:
    xtext = xtext.replace(
        "ForegroundService.startSession(this);",
        "ForegroundService.startSession(this);\n        droidDeckConsoleMode = getIntent().getBooleanExtra(\"droiddeck_console\", false);\n        if (droidDeckConsoleMode) {\n            ConsoleLogStore.initialize(this);\n            droidDeckRuntimeOverlay = new RuntimeConsoleOverlay(this);\n            droidDeckRuntimeOverlay.stage(\"Motor\", \"Validando contenedor y prefijo\", 6);\n        }"
    )
if 'ConsoleLogStore.append("RUNTIME", line)' not in xtext:
    xtext = xtext.replace(
        "if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));",
        "if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));\n"
        "        if (droidDeckConsoleMode) ProcessHelper.addDebugCallback(line -> ConsoleLogStore.append(\"RUNTIME\", line));"
    )
if "navigationView.setVisibility(droidDeckConsoleMode ? View.GONE : View.VISIBLE);" not in xtext:
    xtext = xtext.replace(
        "NavigationView navigationView = findViewById(R.id.NavigationView);",
        "NavigationView navigationView = findViewById(R.id.NavigationView);\n"
        "        navigationView.setVisibility(droidDeckConsoleMode ? View.GONE : View.VISIBLE);"
    )
old_back = "    public void onBackPressed() {\n        if (environment != null) {"
new_back = (
    "    public void onBackPressed() {\n"
    "        if (droidDeckConsoleMode) {\n"
    "            finish();\n"
    "            return;\n"
    "        }\n"
    "        if (environment != null) {"
)
xtext = xtext.replace(old_back, new_back)
old_four = (
    "        touchpadView.setFourFingersTapCallback(() -> {\n"
    "            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);\n"
    "        });"
)
new_four = (
    "        touchpadView.setFourFingersTapCallback(() -> {\n"
    "            if (!droidDeckConsoleMode && !drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);\n"
    "        });"
)
xtext = xtext.replace(old_four, new_four)

if 'droiddeck_runtime_exit_status' not in xtext:
    old_termination = '        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());'
    new_termination = (
        '        guestProgramLauncherComponent.setTerminationCallback((status) -> {\n'
        '            if (droidDeckConsoleMode) {\n'
        '                runOnUiThread(() -> {\n'
        '                    if (isFinishing() || isDestroyed()) return;\n'
        '                    String purpose = getIntent().getStringExtra("droiddeck_purpose");\n'
        '                    ConsoleLogStore.append("RUNTIME", "Proceso principal finalizado con código " + status + " · " + purpose);\n'
        '                    if ("steam_install".equals(purpose)) {\n'
        '                        waitForDroidDeckSteamInstaller(status, android.os.SystemClock.elapsedRealtime());\n'
        '                    }\n'
        '                    else finishDroidDeckRuntime(status);\n'
        '                });\n'
        '            }\n'
        '            else runOnUiThread(this::exit);\n'
        '        });'
    )
    if old_termination not in xtext:
        raise SystemExit("DroidDeck runtime must return: termination callback not found")
    xtext = xtext.replace(old_termination, new_termination)

if 'intent.getStringExtra("exec_args")' not in xtext:
    old_exec = (
        "            if (intent.hasExtra(\"exec_path\")) {\n"
        "                execPath = WineUtils.unixToDOSPath(intent.getStringExtra(\"exec_path\"), container);\n\n"
        "                if (execPath.endsWith(\".lnk\")) {"
    )
    new_exec = (
        "            if (intent.hasExtra(\"exec_path\")) {\n"
        "                execPath = WineUtils.unixToDOSPath(intent.getStringExtra(\"exec_path\"), container);\n"
        "                String explicitExecArgs = intent.getStringExtra(\"exec_args\");\n"
        "                if (explicitExecArgs != null && !explicitExecArgs.trim().isEmpty()) execArgs = \" \"+explicitExecArgs.trim();\n\n"
        "                if (execPath.endsWith(\".lnk\")) {"
    )
    if old_exec not in xtext:
        raise SystemExit("bloque exec_path esperado no encontrado en XServerDisplayActivity")
    xtext = xtext.replace(old_exec, new_exec)


# Keep the Wine environment alive after SteamSetup's parent process exits.
if 'waitForDroidDeckSteamInstaller' not in xtext:
    installer_anchor = "    private boolean isGenerateWineprefix() {"
    installer_methods = r'''    private void waitForDroidDeckSteamInstaller(int parentStatus, long waitStartedAt) {
        if (isFinishing() || isDestroyed()) return;
        File steamExe = findDroidDeckSteamExecutable();
        long elapsed = android.os.SystemClock.elapsedRealtime() - waitStartedAt;
        int seconds = (int)(elapsed / 1000L);

        if (steamExe != null && steamExe.isFile()) {
            ConsoleLogStore.append("OK", "Steam instalado: " + steamExe.getAbsolutePath());
            if (droidDeckRuntimeOverlay != null) {
                droidDeckRuntimeOverlay.stage("Steam", "steam.exe encontrado · instalación completada", 100);
            }
            getWindow().getDecorView().postDelayed(() -> finishDroidDeckRuntime(0), 350L);
            return;
        }

        if (elapsed >= 120000L) {
            ConsoleLogStore.append("ERROR", "SteamSetup terminó pero steam.exe no apareció tras 120 s.");
            finishDroidDeckRuntime(parentStatus != 0 ? parentStatus : 66);
            return;
        }

        int progress = Math.min(99, 96 + (seconds / 40));
        if (droidDeckRuntimeOverlay != null) {
            droidDeckRuntimeOverlay.stage(
                    "Steam",
                    "Finalizando instalación · esperando procesos hijos · " + seconds + " s",
                    progress
            );
        }

        if (seconds == 0 || seconds % 5 == 0) {
            ConsoleLogStore.append(
                    "INFO",
                    "SteamSetup padre terminó; manteniendo Wine activo · " + seconds + " s"
            );
        }

        getWindow().getDecorView().postDelayed(
                () -> waitForDroidDeckSteamInstaller(parentStatus, waitStartedAt),
                1000L
        );
    }

    private File findDroidDeckSteamExecutable() {
        if (container == null) return null;
        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        String[] paths = {
                "Steam/steam.exe",
                "Program Files (x86)/Steam/steam.exe",
                "Program Files/Steam/steam.exe",
                "users/xuser/AppData/Local/Steam/steam.exe"
        };

        for (String path : paths) {
            File file = new File(driveC, path);
            if (file.isFile()) return file;
        }

        return findDroidDeckSteamRecursive(driveC, 0);
    }

    private File findDroidDeckSteamRecursive(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 7) return null;
        if ("windows".equalsIgnoreCase(dir.getName())) return null;

        File[] children = dir.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (child.isFile() && "steam.exe".equalsIgnoreCase(child.getName())) return child;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                File found = findDroidDeckSteamRecursive(child, depth + 1);
                if (found != null) return found;
            }
        }

        return null;
    }

    private void finishDroidDeckRuntime(int status) {
        if (droidDeckSteamWatchdog != null) {
            droidDeckSteamWatchdog.stop();
            droidDeckSteamWatchdog = null;
        }

        if (droidDeckRuntimeOverlay != null) {
            droidDeckRuntimeOverlay.close();
            droidDeckRuntimeOverlay = null;
        }

        Intent result = new Intent();
        result.putExtra("droiddeck_runtime_exit_status", status);
        result.putExtra("droiddeck_runtime_purpose", getIntent().getStringExtra("droiddeck_purpose"));
        setResult(Activity.RESULT_OK, result);
        finish();
    }

'''
    if installer_anchor not in xtext:
        raise SystemExit("ancla instalador DroidDeck no encontrada")
    xtext = xtext.replace(installer_anchor, installer_methods + installer_anchor)

# DroidDeck runtime telemetry: hide Winlator preloaders and expose real milestones.
xtext = xtext.replace(
    "                preloaderDialog.show(R.string.updating_system_files);",
    "                if (droidDeckConsoleMode && droidDeckRuntimeOverlay != null) {\n"
    "                    droidDeckRuntimeOverlay.stage(\"Wine\", \"Actualizando prefijo de Windows\", 14);\n"
    "                }\n"
    "                else preloaderDialog.show(R.string.updating_system_files);"
)

xtext = xtext.replace(
    "        preloaderDialog.show(R.string.starting_up);",
    "        if (droidDeckConsoleMode && droidDeckRuntimeOverlay != null) {\n"
    "            droidDeckRuntimeOverlay.stage(\"Motor\", \"Inicializando servidor gráfico\", 12);\n"
    "        }\n"
    "        else preloaderDialog.show(R.string.starting_up);"
)

xtext = xtext.replace(
    "                    xServerView.getRenderer().setCursorVisible(true);\n"
    "                    preloaderDialog.closeOnUiThread();\n"
    "                    flags[0] = true;",
    "                    xServerView.getRenderer().setCursorVisible(true);\n"
    "                    if (droidDeckConsoleMode && droidDeckRuntimeOverlay != null) {\n"
    "                        String runtimePurpose = getIntent().getStringExtra(\"droiddeck_purpose\");\n"
    "                        if (\"steam_client\".equals(runtimePurpose)) {\n"
    "                            if (droidDeckSteamWatchdog != null) {\n"
    "                                droidDeckSteamWatchdog.stop();\n"
    "                                droidDeckSteamWatchdog = null;\n"
    "                            }\n"
    "                            droidDeckRuntimeOverlay.ready(\"Ventana de Steam lista\");\n"
    "                        }\n"
    "                        else {\n"
    "                            droidDeckRuntimeOverlay.stage(\"Steam\", \"Instalador trabajando en segundo plano\", 96);\n"
    "                        }\n"
    "                    }\n"
    "                    else preloaderDialog.closeOnUiThread();\n"
    "                    flags[0] = true;"
)

xtext = xtext.replace(
    "            if (!isGenerateWineprefix()) {\n"
    "                setupWineSystemFiles();\n"
    "                extractGraphicsDriverFiles();\n"
    "                changeWineAudioDriver();\n"
    "            }\n"
    "            setupXEnvironment();",
    "            if (!isGenerateWineprefix()) {\n"
    "                if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Wine\", \"Preparando registro, DLL y prefijo\", 18);\n"
    "                setupWineSystemFiles();\n"
    "                if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Wine\", \"Wine y prefijo listos\", 42);\n"
    "                if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Gráficos\", \"Preparando Vortek / Gladio\", 43);\n"
    "                extractGraphicsDriverFiles();\n"
    "                if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Gráficos\", \"Controlador gráfico listo\", 64);\n"
    "                if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Audio\", \"Configurando audio de Wine\", 65);\n"
    "                changeWineAudioDriver();\n"
    "                if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Audio\", \"Audio listo\", 72);\n"
    "            }\n"
    "            if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Runtime\", \"Creando entorno Linux / Windows\", 73);\n"
    "            setupXEnvironment();"
)

xtext = xtext.replace(
    "    private void setupXEnvironment() {\n"
    "        String rootPath = rootFS.getRootDir().getPath();",
    "    private void setupXEnvironment() {\n"
    "        if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Runtime\", \"Preparando variables de Wine y Box64\", 76);\n"
    "        String rootPath = rootFS.getRootDir().getPath();"
)

xtext = xtext.replace(
    "        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {\n"
    "            VortekRendererComponent.Options options",
    "        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {\n"
    "            if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Gráficos\", \"Iniciando Vortek para la GPU\", 84);\n"
    "            VortekRendererComponent.Options options"
)

xtext = xtext.replace(
    "        guestProgramLauncherComponent.setEnvVars(envVars);",
    "        if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Box64 + Wine\", \"Preparando el ejecutable de Steam\", 88);\n"
    "        guestProgramLauncherComponent.setEnvVars(envVars);"
)

xtext = xtext.replace(
    "        environment.startEnvironmentComponents();\n\n"
    "        winHandler.start();",
    "        if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Box64 + Wine\", \"Iniciando servicios y proceso Windows\", 92);\n"
    "        environment.startEnvironmentComponents();\n\n"
    "        winHandler.start();\n"
    "        if (droidDeckRuntimeOverlay != null) droidDeckRuntimeOverlay.stage(\"Steam\", \"Esperando la primera ventana real\", 95);\n"
    "        if (droidDeckConsoleMode && droidDeckRuntimeOverlay != null && \"steam_client\".equals(getIntent().getStringExtra(\"droiddeck_purpose\"))) {\n"
    "            droidDeckSteamWatchdog = new SteamRuntimeWatchdog(this, droidDeckRuntimeOverlay);\n"
    "            droidDeckSteamWatchdog.start();\n"
    "        }"
)

xtext = xtext.replace(
    "        if (environment != null) {\n"
    "            xServerView.onResume();\n"
    "            environment.onResume();\n"
    "        }",
    "        if (environment != null) {\n"
    "            xServerView.onResume();\n"
    "            if (!droidDeckConsoleMode) environment.onResume();\n"
    "        }"
)

xtext = xtext.replace(
    "        if (environment != null && !isInPictureInPictureMode()) {\n"
    "            environment.onPause();\n"
    "            xServerView.onPause();\n"
    "        }",
    "        if (environment != null && !isInPictureInPictureMode()) {\n"
    "            if (!droidDeckConsoleMode) environment.onPause();\n"
    "            xServerView.onPause();\n"
    "        }"
)

xtext = xtext.replace(
    "    protected void onDestroy() {\n"
    "        winHandler.stop();",
    "    protected void onDestroy() {\n"
    "        if (droidDeckSteamWatchdog != null) {\n"
    "            droidDeckSteamWatchdog.stop();\n"
    "            droidDeckSteamWatchdog = null;\n"
    "        }\n"
    "        if (droidDeckRuntimeOverlay != null) {\n"
    "            droidDeckRuntimeOverlay.close();\n"
    "            droidDeckRuntimeOverlay = null;\n"
    "        }\n"
    "        winHandler.stop();"
)

xtext = xtext.replace(
    "                    ConsoleLogStore.append(\"RUNTIME\", \"Proceso finalizado con código \" + status);\n"
    "                    Intent result = new Intent();",
    "                    ConsoleLogStore.append(\"RUNTIME\", \"Proceso finalizado con código \" + status);\n"
    "                    if (droidDeckSteamWatchdog != null) {\n"
    "                        droidDeckSteamWatchdog.stop();\n"
    "                        droidDeckSteamWatchdog = null;\n"
    "                    }\n"
    "                    if (droidDeckRuntimeOverlay != null) {\n"
    "                        droidDeckRuntimeOverlay.close();\n"
    "                        droidDeckRuntimeOverlay = null;\n"
    "                    }\n"
    "                    Intent result = new Intent();"
)

# Guard against silent patch drift.
for token in [
    'droidDeckRuntimeOverlay.stage("Wine"',
    'droidDeckRuntimeOverlay.stage("Gráficos"',
    'droidDeckRuntimeOverlay.stage("Audio"',
    'droidDeckRuntimeOverlay.stage("Box64 + Wine"',
    'droidDeckRuntimeOverlay.ready("Ventana de Steam lista")',
    'droidDeckSteamWatchdog = new SteamRuntimeWatchdog',
    'if (!droidDeckConsoleMode) environment.onPause();',
    'if (!droidDeckConsoleMode) environment.onResume();',
]:
    if token not in xtext:
        raise SystemExit("telemetría DroidDeck no aplicada: falta " + token)

xserver.write_text(xtext, encoding="utf-8")

# Rebrand foreground runtime service and keep DroidDeck sessions alive when backgrounded.
fgtext = foreground_service.read_text(encoding="utf-8")
fgtext = fgtext.replace('"Winlator:ForegroundService"', '"DroidDeck:ForegroundService"')
fgtext = fgtext.replace('"Winlator"', '"DroidDeck"')
foreground_service.write_text(fgtext, encoding="utf-8")

nutext = notification_utils.read_text(encoding="utf-8")
nutext = nutext.replace('"winlator_foreground_service"', '"droiddeck_foreground_service"')
nutext = nutext.replace('"Winlator Foreground Service"', '"DroidDeck en segundo plano"')
nutext = nutext.replace('"Allows to display Winlator foreground notifications"', '"Mantiene Steam y el motor de DroidDeck activos en segundo plano"')
notification_utils.write_text(nutext, encoding="utf-8")

print("Overlay DroidDeck M7 aplicado.")
print("Base esperada:", EXPECTED_SHA)
