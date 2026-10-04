#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$ROOT/winlator-app}"
ARCHIVE="${2:?Pass the verified real Steam archive}"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT
JAVA_SDK="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
SDK="$SRC/app/src/main/cpp/steamarchive/third_party/lzma_sdk/C"
cc -O2 -std=c11 -Wall -Wextra -fPIC -shared -D_POSIX_C_SOURCE=200809L \
  -DZ7_PPMD_SUPPORT -DZ7_EXTRACT_ONLY -pthread \
  -I"$JAVA_SDK/include" -I"$JAVA_SDK/include/linux" -I"$SDK" \
  "$ROOT/overlay/app/src/main/cpp/steamarchive/steamarchive.c" "$SDK"/*.c \
  -o "$TEST_ROOT/libsteamarchive.so"
"${JAVAC:-javac}" -d "$TEST_ROOT" \
  "$ROOT/overlay/app/src/main/java/com/winlator/console/NativeSteamArchive.java" \
  "$ROOT/overlay/app/src/main/java/com/winlator/console/SteamInstallation.java" \
  "$ROOT/tools/tests/NativeSteamArchiveTest.java"
java -Xmx64m -Xcheck:jni -Djava.library.path="$TEST_ROOT" -cp "$TEST_ROOT" \
  com.winlator.console.NativeSteamArchiveTest "$ARCHIVE" "$TEST_ROOT/staging"
