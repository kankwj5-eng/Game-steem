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
    root / "overlay/app/src/main/res/layout/console_launcher_activity.xml",
    root / "overlay/app/src/main/res/drawable/console_status_chip.xml",
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
    '"/S"',
    "Range",
    "formatSpeed",
    "handleRuntimeResult",
    "WineUtils.unixToDOSPath",
    ".wine/drive_c/DroidDeck",
    "waitForSteamAfterInstall",
    "Tamaño incompleto",
    "runtimeActive",
    "retry()",
]:
    if token not in controller:
        raise SystemExit(f"controlador incompleto: falta {token}")

for token in ["requestRequiredPermissions", "onActivityResult", "TVCurrentPercent", "StepPermissionsStatus"]:
    if token not in activity:
        raise SystemExit(f"launcher incompleto: falta {token}")

patcher = (root / "tools/apply_overlay.py").read_text()
for token in [
    "/data/data/com.droiddeck.console/",
    "runOnUiThread",
    'versionName "0.4.0-m4"',
]:
    if token not in patcher:
        raise SystemExit(f"parche M4 incompleto: falta {token}")

print("Overlay M4 válido: Steam en C:, rutas DroidDeck, progreso real, retorno seguro y diagnóstico presentes.")
