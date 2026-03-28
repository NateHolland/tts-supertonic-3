#!/bin/bash
#
# One-time project setup after cloning.
# Creates gradle.properties, downloads model files, and generates the Xcode project.
#
# Usage: ./setup.sh

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# ── 1. gradle.properties ─────────────────────────────────────────────

GRADLE_PROPS="gradle.properties"

if [ -f "$GRADLE_PROPS" ]; then
    echo "=== gradle.properties already exists, skipping ==="
else
    echo "=== Creating gradle.properties ==="
    cat > "$GRADLE_PROPS" <<'EOF'
android.useAndroidX=true
org.gradle.jvmargs=-Xmx4g

# ── Maven Central publishing (optional — only needed for ./gradlew publish) ──
# mavenCentralUsername=
# mavenCentralPassword=
# signing.keyId=
# signing.password=
# signing.secretKeyRingFile=
EOF
    echo "  Created $GRADLE_PROPS"
    echo "  (Edit it to add Maven Central credentials if you need to publish.)"
fi

# ── 2. Model files ───────────────────────────────────────────────────

echo ""
echo "=== Downloading model files ==="
./download_model.sh

# ── 3. Xcode project (if xcodegen is available) ─────────────────────

echo ""
if command -v xcodegen &>/dev/null; then
    echo "=== Generating Xcode project ==="
    cd iosApp
    xcodegen generate
    cd "$PROJECT_DIR"
    echo "  Generated iosApp/TTSSample.xcodeproj"
else
    echo "=== Skipping Xcode project generation (xcodegen not installed) ==="
    echo "  Install with: brew install xcodegen"
fi

echo ""
echo "=== Setup complete ==="
echo ""
echo "Next steps:"
echo "  Android:  ./gradlew :sample:assembleDebug"
echo "  iOS:      cd iosApp && open TTSSample.xcodeproj"
