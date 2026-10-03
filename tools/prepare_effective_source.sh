#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
SRC="$ROOT/winlator-app"
LOCKED_SHA="3981d86efa4f333b2a34a7da8b6521476cd8c8b9"

rm -rf "$SRC"
git init "$SRC"
git -C "$SRC" remote add origin https://github.com/brunodev85/winlator-app.git
git -C "$SRC" fetch --depth 1 origin "$LOCKED_SHA"
git -C "$SRC" checkout --detach FETCH_HEAD
test "$(git -C "$SRC" rev-parse HEAD)" = "$LOCKED_SHA"

python3 "$ROOT/tools/check_overlay.py"
python3 "$ROOT/tools/apply_overlay.py" "$SRC"

echo "Effective DroidDeck source prepared at $SRC"
