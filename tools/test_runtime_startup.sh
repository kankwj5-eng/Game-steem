#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$(mktemp -d)"
trap 'rm -rf "$CLASSES"' EXIT
"${JAVAC:-javac}" -d "$CLASSES" \
  "$ROOT/overlay/app/src/main/java/com/winlator/console/ObservedProcess.java" \
  "$ROOT/overlay/app/src/main/java/com/winlator/console/RuntimeStartupPolicy.java" \
  "$ROOT/overlay/app/src/main/java/com/winlator/console/ProcessCpuActivity.java" \
  "$ROOT/overlay/app/src/main/java/com/winlator/console/RuntimeLogThrottle.java" \
  "$ROOT/overlay/app/src/main/java/com/winlator/console/SteamProcessRecovery.java" \
  "$ROOT/tools/tests/SteamProcessRecoveryTest.java" \
  "$ROOT/tools/tests/RuntimeLogThrottleTest.java" \
  "$ROOT/tools/tests/RuntimeStartupTest.java"
java -cp "$CLASSES" com.winlator.console.RuntimeStartupTest
java -cp "$CLASSES" com.winlator.console.RuntimeLogThrottleTest
java -cp "$CLASSES" com.winlator.console.SteamProcessRecoveryTest
