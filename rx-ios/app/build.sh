#!/bin/bash
# Build the iOS device application on macOS with Xcode and JDK 17+.
# Usage: ./rx-ios/app/build.sh device
set -euo pipefail
cd "$(dirname "$0")/../.."
if [ "${1:-device}" != device ]; then
  echo "Usage: $0 device" >&2
  exit 2
fi
./gradlew --no-daemon -q -Prx.iosOnly=true :rx-app:linkReleaseFrameworkIosArm64
FW=rx-app/build/bin/iosArm64/releaseFramework/RexKit.framework
OUT=rx-ios/build/ReX.app
rm -rf "$OUT"
mkdir -p "$OUT/Frameworks"
cp -R "$FW" "$OUT/Frameworks/"
cp rx-ios/app/Info.plist "$OUT/"
mkdir -p "$OUT/licenses"
cp app/src/main/assets/licenses/OFL-Oxanium.txt "$OUT/licenses/"
PARTIAL=rx-ios/build/assets-partial.plist
xcrun actool rx-ios/app/Assets.xcassets --compile "$OUT" --platform iphoneos --minimum-deployment-target 15.0 \
  --target-device iphone --target-device ipad --app-icon AppIcon --output-partial-info-plist "$PARTIAL" \
  --output-format human-readable-text --notices --warnings >/dev/null
/usr/libexec/PlistBuddy -c "Merge $PARTIAL" "$OUT/Info.plist" >/dev/null
RES_PKG=$(sed -n 's/.*packageOfResClass = "\(.*\)".*/\1/p' rx-app/build.gradle.kts)
mkdir -p "$OUT/compose-resources/composeResources/$RES_PKG"
cp -R rx-app/build/generated/compose/resourceGenerator/preparedResources/commonMain/composeResources/. "$OUT/compose-resources/composeResources/$RES_PKG/"
SDK=$(xcrun --sdk iphoneos --show-sdk-path)
xcrun --sdk iphoneos swiftc -parse-as-library -sdk "$SDK" -target arm64-apple-ios15.0 -O \
  -F "$(dirname "$FW")" -framework RexKit -Xlinker -rpath -Xlinker @executable_path/Frameworks \
  rx-ios/app/main.swift -o "$OUT/ReX"
# Leave signing to the user's own provisioning workflow.
rm -rf rx-ios/build/Payload rx-ios/build/ReX.ipa
mkdir -p rx-ios/build/Payload
cp -R "$OUT" rx-ios/build/Payload/
(cd rx-ios/build && zip -qry ReX.ipa Payload && rm -rf Payload)
echo "BUILT rx-ios/build/ReX.ipa"
