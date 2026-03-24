#!/bin/bash

export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd "$(dirname "$0")/.."

echo "=== Regenerating Xcode project ==="
cd iosApp
xcodegen generate 2>&1
cd ..

echo "=== Verifying OTHER_LDFLAGS in project ==="
grep "OTHER_LDFLAGS\|OTHER_LINKER" iosApp/TTSSample.xcodeproj/project.pbxproj | head -5

echo "=== Building iOS app (device, Debug) ==="
xcodebuild \
  -project iosApp/TTSSample.xcodeproj \
  -target TTSSample \
  -configuration Debug \
  -sdk iphoneos \
  -arch arm64 \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGN_IDENTITY="" \
  2>&1 | tee /tmp/xcode-build-output.txt | grep -E "error:|BUILD SUCCEEDED|BUILD FAILED" | tail -5

echo ""
echo "=== Linker errors (if any) ==="
grep -E "Undefined symbol|ld: symbol|_OrtGet|force_load" /tmp/xcode-build-output.txt | head -10

echo ""
echo "=== Result ==="
grep "BUILD SUCCEEDED\|BUILD FAILED" /tmp/xcode-build-output.txt | tail -1
