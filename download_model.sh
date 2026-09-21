#!/bin/bash
#
# Downloads the Supertone supertonic-3 TTS model from HuggingFace
# and places files in the correct directories for Android and iOS builds.
#
# License: OpenRAIL-M (see https://huggingface.co/Supertone/supertonic-3)
#
# Usage: ./download_model.sh

set -euo pipefail

REPO="Supertone/supertonic-3"
BASE_URL="https://huggingface.co/${REPO}/resolve/main"

IOS_DIR="iosApp/Supertone_supertonic-3"
ANDROID_DIR="tts-model-pack/src/main/assets/tts_models/Supertone_supertonic-3"

# Files to download — remote paths relative to repo root
# Format: "remote_path:local_filename"
DOWNLOADS=(
  "onnx/duration_predictor.onnx:duration_predictor.onnx"
  "onnx/text_encoder.onnx:text_encoder.onnx"
  "onnx/vector_estimator.onnx:vector_estimator.onnx"
  "onnx/vocoder.onnx:vocoder.onnx"
  "onnx/tts.json:tts.json"
  "onnx/unicode_indexer.json:unicode_indexer.json"
  "voice_styles/F1.json:F1.json"
  "voice_styles/F2.json:F2.json"
  "voice_styles/F3.json:F3.json"
  "voice_styles/F4.json:F4.json"
  "voice_styles/F5.json:F5.json"
  "voice_styles/M1.json:M1.json"
  "voice_styles/M2.json:M2.json"
  "voice_styles/M3.json:M3.json"
  "voice_styles/M4.json:M4.json"
  "voice_styles/M5.json:M5.json"
)

echo "=== Supertone supertonic-3 Model Downloader ==="
echo "License: OpenRAIL-M"
echo ""

# Create directories
mkdir -p "$IOS_DIR" "$ANDROID_DIR"

# Download to iOS dir first, then copy to Android
for entry in "${DOWNLOADS[@]}"; do
  remote="${entry%%:*}"
  local_name="${entry##*:}"
  dest="$IOS_DIR/$local_name"
  if [ -f "$dest" ]; then
    echo "  [skip] $local_name (already exists)"
    continue
  fi
  echo "  [download] $local_name ..."
  curl -fSL "${BASE_URL}/${remote}" -o "$dest"
done

echo ""
echo "=== Copying to Android directory ==="
for entry in "${DOWNLOADS[@]}"; do
  local_name="${entry##*:}"
  src="$IOS_DIR/$local_name"
  dest="$ANDROID_DIR/$local_name"
  if [ -f "$dest" ] && cmp -s "$src" "$dest"; then
    echo "  [skip] $local_name (already up to date)"
  else
    cp "$src" "$dest"
    echo "  [copy] $local_name"
  fi
done

echo ""
echo "=== Done ==="
echo "iOS models:     $IOS_DIR/"
echo "Android models: $ANDROID_DIR/"
echo ""
du -sh "$IOS_DIR" "$ANDROID_DIR"
