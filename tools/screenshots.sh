#!/usr/bin/env bash
# Renders every scene of src/test/scenes/*.txt to build/screenshots/<file>-<name>.png (needs a display,
# e.g. xvfb-run), checks each image (not black, no missing-texture magenta) and writes an index.html gallery.
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/screenshots
rm -rf "$out" && mkdir -p "$out"
./gradlew -q installDist
fail=0
for f in src/test/scenes/*.txt; do
  base=$(basename "$f" .txt)
  while read -r name opts; do
    [[ -z "$name" || "$name" == \#* ]] && continue
    shot="$out/$base-$name.png"
    world=$(mktemp -d)
    # shellcheck disable=SC2086
    if ! timeout 300 build/install/mynecraft/bin/mynecraft --world "$world/w" $opts --screenshot "$shot" > "$out/$base-$name.log" 2>&1; then
      echo "FAIL $base-$name (see log)"; fail=1; continue
    fi
    python3 tools/check_shot.py "$shot" || fail=1
  done < "$f"
done
{
  echo '<!doctype html><meta charset="utf-8"><title>Captures</title><body style="background:#222;color:#eee;font-family:sans-serif">'
  for p in "$out"/*.png; do n=$(basename "$p"); echo "<figure style=\"display:inline-block\"><img src=\"$n\" width=640><figcaption>$n</figcaption></figure>"; done
} > "$out/index.html"
exit $fail
