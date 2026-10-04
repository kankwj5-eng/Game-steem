#!/usr/bin/env bash
set -euo pipefail
APP=com.droiddeck.archiveprobe
mkdir -p android-probe-results
APK="$(find tools/android_archive_probe/build/outputs/apk/debug -name '*.apk' -print -quit)"
test -s "$APK"
adb install -r "$APK"
adb shell wm size 1280x720
adb shell wm density 240
adb shell run-as "$APP" mkdir -p files
adb shell -T "run-as $APP sh -c 'cat > files/steam-legacy.7z'" < steam-contract/steam-legacy.7z
adb logcat -c
adb shell am start -W -n "$APP/com.winlator.console.ArchiveProbeActivity"
for attempt in $(seq 1 240); do
  if adb shell run-as "$APP" cat files/result.txt > android-probe-results/result.txt 2>/dev/null; then break; fi
  sleep 2
done
adb logcat -d > android-probe-results/logcat.txt
adb exec-out screencap -p > android-probe-results/landscape.png
adb shell run-as "$APP" cat files/ui-result.txt > android-probe-results/ui-result.txt
cat android-probe-results/result.txt
grep -q '^PASS' android-probe-results/result.txt
grep -q '^PASS' android-probe-results/ui-result.txt
! grep -q 'FAIL UI:' android-probe-results/logcat.txt
