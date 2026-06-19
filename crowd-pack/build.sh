#!/usr/bin/env bash
# Rebuild ballgame-crowd-pack.zip from plugin sources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/src/main/resources/crowd-sounds"
OUT="$(cd "$(dirname "$0")" && pwd)/ballgame-crowd-pack.zip"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/assets/ballgame/sounds/crowd"
cp "$SRC/pack.mcmeta" "$TMP/"
cp "$SRC/assets/ballgame/sounds.json" "$TMP/assets/ballgame/"
cp "$SRC/ogg/"*.ogg "$TMP/assets/ballgame/sounds/crowd/"

(cd "$TMP" && zip -qr "$OUT" .)
echo "Built $OUT ($(du -h "$OUT" | cut -f1))"
