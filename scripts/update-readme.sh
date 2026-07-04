#!/usr/bin/env bash
# Regenerates the "Example output" section of README.md from a live run of the demo app,
# so the README never drifts from what the agent actually renders.
set -euo pipefail
cd "$(dirname "$0")/.."

raw=$(sbt -batch demo/run 2>&1)

# sbt echoes the forked process's stdout with an "[info] " prefix and its own build
# chatter under other tags (info for compile steps, error for the fork's stderr, success
# for the timing line) — strip the prefix, then start at the demo's own first line.
output=$(printf '%s\n' "$raw" | sed -n 's/^\[info\] //p' | awk '/^-- /{f=1} f')

if [ -z "$output" ]; then
  echo "update-readme: no demo output captured — sbt run may have failed:" >&2
  printf '%s\n' "$raw" >&2
  exit 1
fi

# JavaApp re-runs the same Java zoo as proof it needs no Scala on the classpath; only its
# "Method references" section is new content worth including here, so trim to that.
raw_javaapp=$(sbt -batch 'demo/runMain demo.JavaApp' 2>&1)
output_javaapp=$(printf '%s\n' "$raw_javaapp" | sed -n 's/^\[info\] //p' | awk '/^-- Method references/{f=1} f')

if [ -z "$output_javaapp" ]; then
  echo "update-readme: no JavaApp output captured — sbt runMain may have failed:" >&2
  printf '%s\n' "$raw_javaapp" >&2
  exit 1
fi

output="$output"$'\n'"$output_javaapp"

OUTPUT="$output" python3 - <<'PY'
import os, re, pathlib

output = os.environ["OUTPUT"]
readme = pathlib.Path("README.md")
text = readme.read_text()
block = "<!-- DEMO_OUTPUT:START -->\n```\n" + output + "\n```\n<!-- DEMO_OUTPUT:END -->"
new_text, n = re.subn(
    r"<!-- DEMO_OUTPUT:START -->.*<!-- DEMO_OUTPUT:END -->",
    lambda _: block,
    text,
    flags=re.DOTALL,
)
if n == 0:
    raise SystemExit("update-readme: README.md is missing the DEMO_OUTPUT markers")
readme.write_text(new_text)
PY

echo "README.md updated with latest demo output."
