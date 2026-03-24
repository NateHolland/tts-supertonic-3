#!/bin/bash
# Downloads a Piper TTS model (VITS architecture) for use with the TTS SDK.
#
# Usage:
#   ./download_piper_model.sh
#
# This downloads the en_US-lessac-medium voice (single speaker, 22050 Hz)
# which produces high-quality English speech in a ~64MB ONNX model.
#
# The files are placed in:
#   tts-model-pack/src/main/assets/tts_models/piper_en_US_lessac_medium/

set -euo pipefail

MODEL_DIR="tts-model-pack/src/main/assets/tts_models/piper_en_US_lessac_medium"
BASE_URL="https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium"

mkdir -p "$MODEL_DIR"

echo "Downloading Piper en_US-lessac-medium model..."
echo "  Model: ~64 MB"
echo "  Config: ~2 KB"
echo ""

# Download ONNX model
if [ -f "$MODEL_DIR/model.onnx" ] && [ "$(stat -f%z "$MODEL_DIR/model.onnx" 2>/dev/null || stat -c%s "$MODEL_DIR/model.onnx" 2>/dev/null)" -gt 1000 ]; then
    echo "[skip] model.onnx already exists"
else
    echo "[download] model.onnx ..."
    curl -L -o "$MODEL_DIR/model.onnx" "${BASE_URL}/en_US-lessac-medium.onnx?download=true"
fi

# Download config JSON
if [ -f "$MODEL_DIR/model.onnx.json" ]; then
    echo "[skip] model.onnx.json already exists"
else
    echo "[download] model.onnx.json ..."
    curl -L -o "$MODEL_DIR/model.onnx.json" "${BASE_URL}/en_US-lessac-medium.onnx.json?download=true"
fi

echo ""
echo "Done! Model files:"
ls -lh "$MODEL_DIR/"
echo ""
echo "You can now build and run the sample app."
