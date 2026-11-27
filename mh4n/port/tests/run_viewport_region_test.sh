#!/bin/sh
# Builds viewport_region_test.cpp for the host (g++ -std=c++17, warnings as errors) and runs it; the exit code is the number
# of tests that failed. Usage: run_viewport_region_test.sh [build folder] (default: $TMPDIR/viewport_region_test)
set -e
here=$(cd "$(dirname "$0")" && pwd)
build=${1:-${TMPDIR:-/tmp}/viewport_region_test}
mkdir -p "$build"
g++ -std=c++17 -O1 -Wall -Wextra -Werror "$here/viewport_region_test.cpp" -o "$build/viewport_region_test"
"$build/viewport_region_test"
