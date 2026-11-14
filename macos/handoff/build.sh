#!/bin/sh
# Builds "LibrePods Handoff.app" with the Command Line Tools alone (no Xcode project).
#   ./build.sh            build into build/
#   ./build.sh install    also copy it to ~/Applications
set -e
cd "$(dirname "$0")"
NAME="LibrePods Handoff.app"
# Built and signed outside the checkout: folders under Documents pick up Finder attributes,
# which codesign refuses.
WORK="$(mktemp -d)"
APP="$WORK/$NAME"
mkdir -p "$APP/Contents/MacOS"
cp Info.plist "$APP/Contents/Info.plist"
swiftc -O -swift-version 5 \
    -framework AppKit -framework IOBluetooth -framework CoreBluetooth -framework CoreAudio -framework ServiceManagement \
    Sources/*.swift -o "$APP/Contents/MacOS/LibrePodsHandoff"
# Ad-hoc signature: enough for macOS to remember the Bluetooth permission for this build.
codesign --force --sign - "$APP"
rm -rf "build/$NAME"
mkdir -p build
ditto "$APP" "build/$NAME"
echo "built build/$NAME"
if [ "$1" = "install" ]; then
    mkdir -p "$HOME/Applications"
    rm -rf "$HOME/Applications/$NAME"
    ditto "$APP" "$HOME/Applications/$NAME"
    echo "installed to ~/Applications/$NAME"
fi
rm -rf "$WORK"
