# Tests of the native layer

`run_tests.sh [build folder]` builds the layer (`../hf_core.c` over the applier in `../../c`) with the host's gcc, every
file with `-Wall -Wextra -Werror`, and runs `hf_test.c` over `fixtures/`. The exit code is the number of tests that
failed. Gradle's `externalNativeBuild` builds the same files for the phone (`../CMakeLists.txt`); the JNI
(`../hf_jni.c`) is built there only.

## The fixtures

Everything in `fixtures/` is made up: no game data. `make_fixtures.py <Heavenfall checkout> <fixtures> <scratch>` makes
them with Heavenfall's own synthetic builders (`tests/install/title/test_patchset_android.py` and the helpers it
imports: a made-up game, its update as a CIA and as a bare NCCH, MH XX, the mod, the release's shared file), writes the
sets with Heavenfall's own writer (`core/install/title/patchset.py`), and takes each set's expected tree from the mod
folder for plain files and from Heavenfall's reference driver (`tests/install/title/patchset_driver.c`, built with the
host gcc) for the ARCs, whose deflated bytes only a deflate gives.

| File | What it is |
|---|---|
| `game.cxi`, `game_eur.cxi` | the American and the European base game |
| `update.cia`, `update.cxi` | the American update, as a decrypted CIA and as a bare NCCH |
| `update_eur.cia`, `update_other.cia`, `update_locked.cia` | the European update, another revision of the American one, an encrypted one |
| `xx.cxi` | MH XX |
| `android.hfpatch`, `shared.hfpack` | a format 4 set with the release's shared file |
| `android_xx.hfpatch` | a format 4 set made with MH XX |
| `format3.hfpatch`, `set_newer.hfpatch`, `set_cut.hfpatch`, `bad_path.hfpatch` | a console set; a set of a newer format; a cut set; a set naming a file outside the mod's folder |
| `junk.bin` | a file that is no game |
| `expected.txt` | every file each set makes (size and SHA-256), the code hashes, the byte to flip in the game, the update's identity |

Run `make_fixtures.py` again only when the set format changes.
