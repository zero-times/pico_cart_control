#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets"
MODEL_DIR="$ASSETS_DIR/model-cn"
TMP_DIR="${TMPDIR:-/tmp}/pico-cart-vosk"
MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
MODEL_ZIP="$TMP_DIR/vosk-model-small-cn-0.22.zip"

mkdir -p "$TMP_DIR" "$ASSETS_DIR"

if [[ ! -f "$MODEL_ZIP" ]]; then
  curl -L "$MODEL_URL" -o "$MODEL_ZIP"
fi

rm -rf "$MODEL_DIR" "$TMP_DIR/vosk-model-small-cn-0.22"
unzip -q "$MODEL_ZIP" -d "$TMP_DIR"
mv "$TMP_DIR/vosk-model-small-cn-0.22" "$MODEL_DIR"

echo "Vosk Chinese model installed at: $MODEL_DIR"
echo "Now rebuild the APK: cd $ROOT_DIR && ./gradlew :app:assembleDebug"
