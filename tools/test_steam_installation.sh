#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEST_CLASSES="$(mktemp -d)"
trap 'rm -rf "$TEST_CLASSES"' EXIT
"${JAVAC:-javac}" -d "$TEST_CLASSES" \
  "$ROOT/overlay/app/src/main/java/com/winlator/console/SteamInstallation.java" \
  "$ROOT/tools/tests/SteamInstallationTest.java"
java -cp "$TEST_CLASSES" com.winlator.console.SteamInstallationTest
