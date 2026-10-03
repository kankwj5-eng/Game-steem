#!/usr/bin/env python3
from pathlib import Path
import xml.etree.ElementTree as ET
import py_compile

root = Path(__file__).resolve().parents[1]
required = [
    root / "upstream.lock",
    root / "overlay/app/src/main/java/com/winlator/ConsoleLauncherActivity.java",
    root / "overlay/app/src/main/java/com/winlator/console/ConsoleBootstrapController.java",
    root / "overlay/app/src/main/java/com/winlator/console/ConsoleLogStore.java",
    root / "overlay/app/src/main/java/com/winlator/console/BootstrapStep.java",
    root / "overlay/app/src/main/java/com/winlator/console/RuntimeConsoleOverlay.java",
    root / "overlay/app/src/main/java/com/winlator/console/SteamRuntimeWatchdog.java",
    root / "overlay/app/src/main/res/layout/droiddeck_runtime_overlay.xml",
    root / "overlay/app/src/main/res/layout/console_launcher_activity.xml",
    root / "overlay/app/src/main/res/drawable/console_status_chip.xml",
    root / "overlay/app/src/main/res/drawable/ic_droiddeck_logo.xml",
]
for p in required:
    if not p.is_file():
        raise SystemExit(f"falta {p.relative_to(root)}")

for p in (root / "overlay/app/src/main/res").rglob("*.xml"):
    ET.parse(p)

for p in (root / "tools").glob("*.py"):
    py_compile.compile(str(p), doraise=True)

controller = (root / "overlay/app/src/main/java/com/winlator/console/ConsoleBootstrapController.java").read_text()
activity = (root / "overlay/app/src/main/java/com/winlator/ConsoleLauncherActivity.java").read_text()

for token in [
    "SteamSetup.exe",
    '"/S /D=C:\\\\Steam"',
    "Range",
    "formatSpeed",
    "handleRuntimeResult",
    "WineUtils.unixToDOSPath",
    ".wine/drive_c/DroidDeck",
    "waitForSteamAfterInstall",
    "Tamaño incompleto",
    "runtimeActive",
    "enable_background_protection",
    "enable_background_wakelock",
    "retry()",
]:
    if token not in controller:
        raise SystemExit(f"controlador incompleto: falta {token}")

for token in ["requestRequiredPermissions", "onActivityResult", "TVCurrentPercent", "StepPermissionsStatus"]:
    if token not in activity:
        raise SystemExit(f"launcher incompleto: falta {token}")

runtime_overlay = (root / "overlay/app/src/main/java/com/winlator/console/RuntimeConsoleOverlay.java").read_text()
for token in ["ARRANQUE", "Wine y prefijo", "Vortek / Gladio", "Box64 + Wine", "Ventana de Steam", "waitingTelemetry", "95% ·"]:
    if token not in runtime_overlay:
        raise SystemExit(f"overlay runtime incompleto: falta {token}")

patcher = (root / "tools/apply_overlay.py").read_text()
for token in [
    "/data/data/com.droiddeck.console/",
    "runOnUiThread",
    'versionName "0.7.0-m7"',
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
    "DroidDeck:ForegroundService",
    "waitForDroidDeckSteamInstaller",
    "findDroidDeckSteamExecutable",
    "finishDroidDeckRuntime",
    "120000L",
    "steam.exe no apareció tras 120 s",
]:
    if token not in patcher:
        raise SystemExit(f"parche M7 incompleto: falta {token}")

launcher_xml = (root / "overlay/app/src/main/res/layout/console_launcher_activity.xml").read_text()
runtime_xml = (root / "overlay/app/src/main/res/layout/droiddeck_runtime_overlay.xml").read_text()
for token in ['@drawable/ic_droiddeck_logo', 'DROIDDECK']:
    if token not in launcher_xml or token not in runtime_xml:
        raise SystemExit(f"branding M7 incompleto: falta {token}")

print("Overlay M7 válido: instalador espera hijos, watchdog vivo, Steam C:\\Steam y branding DroidDeck integrado.")
