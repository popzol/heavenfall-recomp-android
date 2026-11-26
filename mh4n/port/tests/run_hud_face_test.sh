#!/bin/sh
# Builds hud_face_test.cpp for the host (g++ -std=c++17, warnings as errors) and runs it; the exit code is the number of
# tests that failed. Usage: run_hud_face_test.sh [build folder] (default: $TMPDIR/hud_face_test)
set -e
here=$(cd "$(dirname "$0")" && pwd)
build=${1:-${TMPDIR:-/tmp}/hud_face_test}
mkdir -p "$build"
g++ -std=c++17 -O1 -Wall -Wextra -Werror "$here/hud_face_test.cpp" -o "$build/hud_face_test"
"$build/hud_face_test"
