#!/bin/sh
# Fails when a release artifact's classes*.dex lacks a class native code looks up by name or a plugin
# (release-required-classes.txt), or has fewer Plugin subclasses than `#min-plugins` there.
# usage: scripts/check-release-dex.sh --apk [file]   default: the single build/outputs/apk/release/*.apk
#        scripts/check-release-dex.sh --aab [file]   default: the single build/outputs/bundle/release/*.aab (base/dex/*.dex)
# Exactly one candidate is required when no file is given. Needs `dexdump` from the Android SDK build-tools.
# Exit: 0 fine, 1 a class missing, 2 the check could not run (fails closed).
here=$(cd "$(dirname "$0")/.." && pwd)
mode=${1:---apk}; file=$2
case $mode in
  --apk) pattern="$here/build/outputs/apk/release/*.apk"; dexes='classes*.dex' ;;
  --aab) pattern="$here/build/outputs/bundle/release/*.aab"; dexes='base/dex/*.dex' ;;
  *) echo "usage: $0 --apk|--aab [file]"; exit 2 ;;
esac
if [ -z "$file" ]; then
  set -- $pattern
  [ $# = 1 ] && [ -f "$1" ] || { echo "check-release-dex: need exactly one artifact matching $pattern, found: $*"; exit 2; }
  file=$1
fi
[ -f "$file" ] || { echo "check-release-dex: no such file $file"; exit 2; }
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
dexdump=$(ls "$sdk"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1)
[ -x "$dexdump" ] || { echo "check-release-dex: no dexdump in $sdk/build-tools"; exit 2; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$file" "$dexes" -d "$tmp" || { echo "check-release-dex: no dex in $file"; exit 2; }
find "$tmp" -name '*.dex' | sort | while read -r dex; do "$dexdump" "$dex" 2>/dev/null; done > "$tmp/dump"
sed -n "s/.*Class descriptor *: *'\(.*\)'.*/\1/p" "$tmp/dump" | sort -u > "$tmp/defined"
[ -s "$tmp/defined" ] || { echo "check-release-dex: dexdump read no classes from $file"; exit 2; }
missing=0
for cls in $(grep -v '^#' "$here/release-required-classes.txt" | grep -v '^$'); do
  if ! grep -qx "L$cls;" "$tmp/defined"; then echo "check-release-dex: MISSING $cls"; missing=1; fi
done
# Strings a release must not contain (the update system's test key): `#forbidden-string <text>` lines.
for str in $(sed -n 's/^#forbidden-string *//p' "$here/release-required-classes.txt"); do
  if grep -aqF -- "$str" "$tmp"/*.dex "$tmp"/*/*.dex 2>/dev/null; then echo "check-release-dex: FORBIDDEN string in the dex: $str"; missing=1; fi
done
# The plugin list is built by reflection from a generated index: a shrunk-away plugin silently disappears.
minimum=$(sed -n 's/^#min-plugins *//p' "$here/release-required-classes.txt")
plugins=$(awk "/Class descriptor/{c=\$4} /Superclass/{ if (\$3 ~ /desklink\\/mobile\\/plugins\\/Plugin;/) print c}" "$tmp/dump" | sort -u | wc -l)
if [ -n "$minimum" ] && [ "$plugins" -lt "$minimum" ]; then
  echo "check-release-dex: only $plugins Plugin subclasses in the dex, expected at least $minimum"; missing=1
fi
[ $missing = 0 ] && echo "check-release-dex: $(basename "$file"): all $(grep -vc '^#\|^$' "$here/release-required-classes.txt") required classes present, $plugins plugins, $(wc -l < "$tmp/defined") classes"
exit $missing
