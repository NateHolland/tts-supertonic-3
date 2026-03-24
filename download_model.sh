#!/bin/bash
#
# Downloads the Supertone supertonic-2 TTS model from HuggingFace
# and places files in the correct directories for Android and iOS builds.
#
# License: OpenRAIL-M (see https://huggingface.co/Supertone/supertonic-2)
#
# Usage: ./download_model.sh

set -euo pipefail

REPO="Supertone/supertonic-2"
BASE_URL="https://huggingface.co/${REPO}/resolve/main"

IOS_DIR="iosApp/Supertone_supertonic-2"
ANDROID_DIR="tts-model-pack/src/main/assets/tts_models/Supertone_supertonic-2"

# All files to download
ONNX_FILES=(
  "duration_predictor.onnx"
  "text_encoder.onnx"
  "vector_estimator.onnx"
  "vocoder.onnx"
)

JSON_FILES=(
  "tts.json"
  "unicode_indexer.json"
  "F1.json" "F2.json" "F3.json" "F4.json" "F5.json"
  "M1.json" "M2.json" "M3.json" "M4.json" "M5.json"
)

ALL_FILES=("${ONNX_FILES[@]}" "${JSON_FILES[@]}")

echo "=== Supertone supertonic-2 Model Downloader ==="
echo "License: OpenRAIL-M"
echo ""

# Create directories
mkdir -p "$IOS_DIR" "$ANDROID_DIR"

# Download to iOS dir first, then copy to Android
for file in "${ALL_FILES[@]}"; do
  dest="$IOS_DIR/$file"
  if [ -f "$dest" ]; then
    echo "  [skip] $file (already exists)"
    continue
  fi
  echo "  [download] $file ..."
  curl -fSL "${BASE_URL}/${file}" -o "$dest"
done

echo ""
echo "=== Copying to Android directory ==="
for file in "${ALL_FILES[@]}"; do
  src="$IOS_DIR/$file"
  dest="$ANDROID_DIR/$file"
  if [ -f "$dest" ] && cmp -s "$src" "$dest"; then
    echo "  [skip] $file (already up to date)"
  else
    cp "$src" "$dest"
    echo "  [copy] $file"
  fi
done

echo ""
echo "=== Done ==="
echo "iOS models:     $IOS_DIR/"
echo "Android models: $ANDROID_DIR/"
echo ""
du -sh "$IOS_DIR" "$ANDROID_DIR"
