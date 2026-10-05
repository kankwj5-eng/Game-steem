#!/usr/bin/env python3
from pathlib import Path
import xml.etree.ElementTree as ET
import py_compile

root = Path(__file__).resolve().parents[1]

required = [
    root / "upstream.lock",
    root / "native-dependencies.lock",
    root / "overlay/app/src/main/java/com/winlator/console/NativeSteamArchive.java",
    root / "overlay/app/src/main/cpp/steamarchive/steamarchive.c",
    root / "overlay/app/src/main/java/com/winlator/console/SteamInstallation.java",
    root / "overlay/app/src/main/java/com/winlator/ConsoleLauncherActivity.java",
    root / "overlay/app/src/main/java/com/winlator/console/ConsoleBootstrapController.java",
    root / "overlay/app/src/main/java/com/winlator/console/ConsoleLogStore.java",
    root / "overlay/app/src/main/java/com/winlator/console/BootstrapStep.java",
    root / "overlay/app/src/main/java/com/winlator/console/RuntimeConsoleOverlay.java",
    root / "overlay/app/src/main/java/com/winlator/console/SteamRuntimeWatchdog.java",
    root / "overlay/app/src/main/java/com/winlator/console/SteamLegacyInstaller.java",
    root / "overlay/app/src/main/java/com/winlator/services/ForegroundService.java",
    root / "overlay/app/src/main/res/layout/droiddeck_runtime_overlay.xml",
    root / "overlay/app/src/main/res/layout/console_launcher_activity.xml",
    root / "overlay/app/src/main/res/drawable/console_status_chip.xml",
    root / "overlay/app/src/main/res/drawable/ic_droiddeck_logo.xml",
    root / "tools/native_security_fixes.py",
    root / "tools/cppcheck-suppressions.txt",
]
for path in required:
    if not path.is_file():
        raise SystemExit(f"falta {path.relative_to(root)}")

for path in (root / "overlay/app/src/main/res").rglob("*.xml"):
    ET.parse(path)

for path in (root / "tools").glob("*.py"):
    py_compile.compile(str(path), doraise=True)

controller = (root / "overlay/app/src/main/java/com/winlator/console/ConsoleBootstrapController.java").read_text()
for token in [
    "SteamLegacyInstaller",
    "Program Files (x86)/Steam/steam.exe",
    "startSteam()",
    "handleRuntimeResult",
    "runtimeActive",
    "enable_background_protection",
    "enable_background_wakelock",
    "retry()",
    "Box64",
    "installSteamLegacy",
    "droiddeck_detailed_logs",
    'detailed ? "warn,err,fixme" : "err"',
    'detailed ? 1 : 0',
    "BOOT · DroidDeck M26 ·",
    "1400L * 1024L * 1024L",
    "1.4 GB libres",
]:
    if token not in controller:
        raise SystemExit(f"controlador M26 incompleto: falta {token}")

# M26 must not regress to hidden Windows installers.
for forbidden in [
    "SteamSetup.exe",
    "steam_install",
    "steam_fallback",
    "launchWindowsDosExecutable",
    "waitForSteamAfterInstall",
]:
    if forbidden in controller:
        raise SystemExit(f"controlador M26 conserva ruta antigua prohibida: {forbidden}")

installer = (root / "overlay/app/src/main/java/com/winlator/console/SteamLegacyInstaller.java").read_text()
for token in [
    "steam-legacy.7z",
    "https://github.com/brunodev85/winlator-addons/releases/download/v1.0.0/steam-legacy.7z",
    "winlator-addons/releases/download/v1.0.0",
    "windows/temp",
    "NativeSteamArchive.inspect",
    "NativeSteamArchive.extract",
    "InstallerError.userMessage",
    "Range",
    "SHA-256",
    "Program Files (x86)",
    "getCanonicalPath",
    "EXTRACT",
    "startInstallerSession",
    "stopInstallerSession",
    "EXPECTED_SHA256",
    "ForegroundService",
    "CACHE CORRUPTA",
    "descargando copia limpia",
    "ALMACENAMIENTO",
    "Espacio insuficiente",
    "1024 * 1024",
    "ConsoleLogStore.flush()",
    "out.getFD().sync()",
    "SteamInstallation.recover",
    "SteamInstallation.writeReceipt",
    "SteamInstallation.promote",

    "HTTP_RANGE_NOT_SATISFIABLE = 416",
    "RANGE 416",
    "reiniciando copia limpia",
    "EXPECTED_ARCHIVE_BYTES = 215_772_926L",
    "Content-Range",
    "parseContentRangeStart",
    "RANGE desalineado",
    "Descarga incompleta:",
    "DOWNLOAD_ATTEMPTS = 4",
    "RETRY_BASE_MS = 1_500L",
    "reintento automático en",
    "caché Steam Legacy liberada",
    "descarga parcial residual",
]:
    if token not in installer:
        raise SystemExit(f"instalador Steam Legacy incompleto: falta {token}")

# Interrupted staging must not be counted as space still needed for a fresh extraction.
if installer.index("FileUtils.delete(stagingRoot)") > installer.index("long usableBytes ="):
    raise SystemExit("recuperación: staging se limpia demasiado tarde para comprobar espacio")
if "!localRecovery && !localArchive && !hasEnoughSpace()" not in controller:
    raise SystemExit("recuperación: caché local vuelve a exigir margen de descarga completa")

foreground = (root / "overlay/app/src/main/java/com/winlator/services/ForegroundService.java").read_text()
for token in [
    "installerActive",
    "startInstallerSession",
    "stopInstallerSession",
    "FOREGROUND_SERVICE_TYPE_DATA_SYNC",
    "ConsoleLauncherActivity",
    "Installer active; keeping process alive after task removal",
    "sessionActive.get() || installerActive.get()",
]:
    if token not in foreground:
        raise SystemExit(f"ForegroundService M26 incompleto: falta {token}")

activity = (root / "overlay/app/src/main/java/com/winlator/ConsoleLauncherActivity.java").read_text()
for token in [
    "requestRequiredPermissions",
    "onActivityResult",
    "TVCurrentPercent",
    "StepPermissionsStatus",
    "controller.destroy()",
    "setDiagnosticsVisible",
    "CBDetailedLogs",
    "logUiActive",
    "protected void onPause()",
    "ConsoleLogStore.addListener(this)",
]:
    if token not in activity:
        raise SystemExit(f"launcher incompleto: falta {token}")

watchdog = (root / "overlay/app/src/main/java/com/winlator/console/SteamRuntimeWatchdog.java").read_text()
for token in [
    "TrafficStats",
    "ProcessHelper.getChildProcesses",
    "scanSteamFiles",
    "Sin actividad observable",
    "fileWriteRate",
    "FILE_SCAN_MS = 5000L",
    "previousFileSampleAt",
    "int[] budget = {1024}",
    "PROCESS_SCAN_MS = 2000L",
    "lastProcessScanAt",
    "steamapps/downloading",
    "processScanRunning",
    "if (running) sampleLinuxProcesses()",
    "now - lastProcessScanAt >= PROCESS_SCAN_MS",
]:
    if token not in watchdog:
        raise SystemExit(f"watchdog incompleto: falta {token}")

runtime_overlay = (root / "overlay/app/src/main/java/com/winlator/console/RuntimeConsoleOverlay.java").read_text()
for token in [
    "ARRANQUE",
    "Wine y prefijo",
    "Vortek / Gladio",
    "Box64 + Wine",
    "Ventana de Steam",
    "waitingTelemetry",
    "Espera ·",
    "detachLogListener",
    "closed || !logSubscribed",
]:
    if token not in runtime_overlay:
        raise SystemExit(f"overlay runtime incompleto: falta {token}")

console_log = (root / "overlay/app/src/main/java/com/winlator/console/ConsoleLogStore.java").read_text()
for token in [
    "BufferedWriter",
    "RUNTIME_CALLBACK",
    "ArrayDeque",
    "UI_NOTIFY_INTERVAL_MS",
    "FILE_LOG_FLUSH_BATCH",
    "postDelayed(uiNotifier",
    "public static synchronized void flush()",
    "MAX_SESSION_FILES = 8",
    "MAX_BUFFER_CHARS = 64 * 1024",
    "new RuntimeLogThrottle(40)",
    "listeners.isEmpty() || uiNotifyScheduled",
    "setDetailedRuntimeLogging",
    "pruneOldLogs",
    "Arrays.sort",
    "UI_NOTIFY_INTERVAL_MS = 250L",
    "\"RUNTIME\".equals(level)",
]:
    if token not in console_log:
        raise SystemExit(f"log M26 no optimizado: falta {token}")

security_fixes = (root / "tools/native_security_fixes.py").read_text()
for token in [
    "sizeof(attenuation)",
    "full_declaration = {0}",
    "1u << i",
    "va_end(cp)",
    "rect_coord_buf",
    "localMemoryInfo",
    "MEMFREE(waitSemaphoresRequest->inputBuffer)",
    "appendShaderString",
    "free(tmpDir)",
]:
    if token not in security_fixes:
        raise SystemExit(f"security fixes incompletos: falta {token}")

patcher = (root / "tools/apply_overlay.py").read_text()
for token in [
    "/data/data/com.droiddeck.console/",
    "runOnUiThread",
    'versionName "1.0.0-m26"',
    "SteamRuntimeWatchdog",
    "droidDeckSteamWatchdog = new SteamRuntimeWatchdog",
    'android:label="DroidDeck"',
    'android:roundIcon="@drawable/ic_droiddeck_logo"',
    "RuntimeConsoleOverlay",
    'droidDeckRuntimeOverlay.stage("Wine"',
    'droidDeckRuntimeOverlay.stage("Gráficos"',
    'droidDeckRuntimeOverlay.stage("Audio"',
    'droidDeckRuntimeOverlay.stage("Box64 + Wine"',
    'droidDeckRuntimeOverlay.ready("Ventana de Steam lista")',
    "COMANDO EFECTIVO",
    "FOREGROUND_SERVICE_DATA_SYNC",
    'android:foregroundServiceType="mediaPlayback|dataSync"',
    "commons-compress:1.28.0",
    "enableLogs && !droidDeckConsoleMode",
    "ProcessHelper.addDebugCallback(ConsoleLogStore.RUNTIME_CALLBACK)",
    "ProcessHelper.removeDebugCallback(ConsoleLogStore.RUNTIME_CALLBACK)",
    "xz:1.12",
]:
    if token not in patcher:
        raise SystemExit(f"parche M26 incompleto: falta {token}")

launcher_xml = (root / "overlay/app/src/main/res/layout/console_launcher_activity.xml").read_text()
runtime_xml = (root / "overlay/app/src/main/res/layout/droiddeck_runtime_overlay.xml").read_text()
for token in ['@drawable/ic_droiddeck_logo', 'DROIDDECK']:
    if token not in launcher_xml or token not in runtime_xml:
        raise SystemExit(f"branding M26 incompleto: falta {token}")

native_archive = (root / "overlay/app/src/main/cpp/steamarchive/steamarchive.c").read_text()
for token in ["DECODER_LIMIT", "budget_alloc", "SzArEx_Extract", "O_NOFOLLOW", "fsync(fd)", "bytes != SzArEx_GetFileSize"]:
    if token not in native_archive:
        raise SystemExit("decoder nativo incompleto: " + token)
if "SevenZFile" in installer:
    raise SystemExit("Steam vuelve a asignar el diccionario 7z en el heap Java")

print("Overlay M26 válido: instalación Steam Legacy nativa, telemetría y seguridad integradas.")

recovery = (root / "overlay/app/src/main/java/com/winlator/console/SteamProcessRecovery.java").read_text()
for token in ["DROIDDECK_STEAM_RUNTIME", "MAX_FILE = 64 * 1024", "pid == self", "fields[19]", "identity.equals(identity"]:
    if token not in recovery:
        raise SystemExit("recuperación M26 incompleta: " + token)
