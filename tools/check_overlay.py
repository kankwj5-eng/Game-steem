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
for token in ["SteamSetup.exe", '"/S"', "Range", "onDeviceInfo", "retry()"]:
    if token not in controller:
        raise SystemExit(f"controlador incompleto: falta {token}")
for token in ["requestRequiredPermissions", "showDiagnostics", "StepPermissionsStatus"]:
    if token not in activity:
        raise SystemExit(f"launcher incompleto: falta {token}")

print("Overlay M2 válido: XML, scripts, permisos, descarga reanudable y diagnóstico presentes.")
