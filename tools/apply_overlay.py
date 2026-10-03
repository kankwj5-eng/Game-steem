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
xserver = src / "app/src/main/java/com/winlator/XServerDisplayActivity.java"

for required in (manifest, rootfs, build_gradle, file_utils, xserver):
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

text = text.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_droiddeck_logo"')
text = text.replace('android:authorities="com.winlator.FileProvider"', 'android:authorities="com.droiddeck.console.FileProvider"')
if "android:requestLegacyExternalStorage=" not in text:
    text = text.replace('android:label="@string/app_name">', 'android:label="@string/app_name"\n        android:requestLegacyExternalStorage="true">')
manifest.write_text(text, encoding="utf-8")

btext = build_gradle.read_text(encoding="utf-8")
btext = btext.replace("applicationId 'com.winlator'", "applicationId 'com.droiddeck.console'")
btext = btext.replace('versionName "11.2"', 'versionName "0.2.0-m2"')
build_gradle.write_text(btext, encoding="utf-8")

for strings in (src / "app/src/main/res").glob("values*/strings.xml"):
    stext = strings.read_text(encoding="utf-8")
    stext = re.sub(r'<string name="app_name">.*?</string>', '<string name="app_name">DroidDeck</string>', stext, count=1)
    strings.write_text(stext, encoding="utf-8")

ftext = file_utils.read_text(encoding="utf-8")
ftext = ftext.replace('"com.winlator.FileProvider"', '"com.droiddeck.console.FileProvider"')
file_utils.write_text(ftext, encoding="utf-8")

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
        "import com.winlator.contentdialog.WineD3DConfigDialog;\nimport com.winlator.console.ConsoleLogStore;"
    )
if "private boolean droidDeckConsoleMode;" not in xtext:
    xtext = xtext.replace(
        "private String screenEffectProfile;",
        "private String screenEffectProfile;\n    private boolean droidDeckConsoleMode;"
    )
if 'droidDeckConsoleMode = getIntent().getBooleanExtra("droiddeck_console", false);' not in xtext:
    xtext = xtext.replace(
        "ForegroundService.startSession(this);",
        "ForegroundService.startSession(this);\n        droidDeckConsoleMode = getIntent().getBooleanExtra(\"droiddeck_console\", false);"
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

xserver.write_text(xtext, encoding="utf-8")

print("Overlay DroidDeck M2 aplicado.")
print("Base esperada:", EXPECTED_SHA)
