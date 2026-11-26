#!/bin/sh
# Builds the native layer for the host (the applier's C as C99, the layer as GNU C99, every file with -Wall -Wextra
# and warnings as errors) and runs its tests over the synthetic fixtures. Usage: run_tests.sh [build folder]
# (default: $TMPDIR/hf_native_test). Needs gcc; the exit code is the number of tests that failed.
set -e
here=$(cd "$(dirname "$0")" && pwd)
shared="$here/../../c"
build=${1:-${TMPDIR:-/tmp}/hf_native_test}
mkdir -p "$build"

objects=""
for name in sha256 romfs ncch dump blz patchset; do
    gcc -std=c99 -O1 -Wall -Wextra -Werror -I "$shared" -c "$shared/$name.c" -o "$build/$name.o"
    objects="$objects $build/$name.o"
done
# the vendored deflate is compiled as upstream's tests do: its own warnings are not ours
gcc -std=c99 -O1 -w -I "$shared" -c "$shared/zlib_mini.c" -o "$build/zlib_mini.o"
objects="$objects $build/zlib_mini.o"
gcc -std=gnu99 -O1 -Wall -Wextra -Werror -I "$shared" -I "$here/.." -c "$here/../hf_core.c" -o "$build/hf_core.o"
gcc -std=gnu99 -O1 -Wall -Wextra -Werror -I "$shared" -I "$here/.." "$here/hf_test.c" "$build/hf_core.o" $objects \
    -o "$build/hf_test"

rm -rf "$build/work"
"$build/hf_test" "$here/fixtures" "$build/work"
