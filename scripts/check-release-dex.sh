#!/bin/sh
# Fails when the release APK's classes*.dex lacks a class native code looks up by name (release-required-classes.txt).
# usage: scripts/check-release-dex.sh [apk]   default: build/outputs/apk/release/*.apk
# Needs `dexdump` from the Android SDK build-tools (ANDROID_HOME, else ~/Android/Sdk).
here=$(cd "$(dirname "$0")/.." && pwd)
apk=${1:-$(ls "$here"/build/outputs/apk/release/*.apk 2>/dev/null | head -1)}
[ -f "$apk" ] || { echo "check-release-dex: no release apk"; exit 2; }
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
dexdump=$(ls "$sdk"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1)
[ -x "$dexdump" ] || { echo "check-release-dex: no dexdump in $sdk/build-tools"; exit 2; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$apk" 'classes*.dex' -d "$tmp" || { echo "check-release-dex: no dex in $apk"; exit 2; }
for dex in "$tmp"/classes*.dex; do "$dexdump" "$dex" 2>/dev/null; done | sed -n "s/.*Class descriptor *: *'\(.*\)'.*/\1/p" | sort -u > "$tmp/defined"
missing=0
for cls in $(grep -v '^#' "$here/release-required-classes.txt" | grep -v '^$'); do
  if ! grep -qx "L$cls;" "$tmp/defined"; then echo "check-release-dex: MISSING $cls"; missing=1; fi
done
[ $missing = 0 ] && echo "check-release-dex: all $(grep -vc '^#\|^$' "$here/release-required-classes.txt") required classes present ($(wc -l < "$tmp/defined") classes in the dex)"
exit $missing
