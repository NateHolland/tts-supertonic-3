#!/bin/bash
#
# Generates the Xcode project for the TTS Supertonic 3 iOS sample app.
#
# Prerequisites:
#   - Xcode 16+ with command-line tools
#   - xcodegen: brew install xcodegen
#   - Build the KMP framework first:
#     cd .. && ./gradlew :sample:linkDebugFrameworkIosSimulatorArm64

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v xcodegen &> /dev/null; then
    echo "xcodegen not found. Install with: brew install xcodegen"
    exit 1
fi

xcodegen generate

echo ""
echo "Xcode project generated successfully!"
echo "Open TTSSample.xcodeproj in Xcode."
