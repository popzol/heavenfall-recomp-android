# Heavenfall for Android

**Heavenfall for Android is a modified version of [4u-native](https://github.com/khorne-hub/4u-native) (MH4U for Android
and PC) by khorne-hub. It is not the original, and it is not made or endorsed by khorne-hub.**

Play your own copy of Monster Hunter 4 Ultimate on Android with the mod Heavenfall, natively and with touch controls made
for phones. It is not an emulator: the game's code is recompiled into native code for your phone, and runs on a runtime
of its own that does the console's work, with its graphics drawn through Vulkan.

[Lee esta guía en español](README.es.md)

**This project includes no game code, game files, ROMs or keys. You need your own copy of the game.** The app and its
releases hold no game data. The game's code is built on your phone, from your own dumps of the game, and stays there:
never share it.

## The app

Heavenfall is one app that sets the mod up, keeps it up to date and starts the game. Nothing of the game is in the app or
in its releases: everything is made on your phone from your own copy of the game.

**What the first run asks for.** A guide walks you through it, and you can leave it and come back:

1. Let the app read files, and choose the folder on your phone where your game files are (or will be).
2. Your own decrypted copy of the American game, Monster Hunter 4 Ultimate (a `.3ds`, `.cci` or `.cxi` file).
3. Your own decrypted copy of the game's 1.3.0 update (a `.cia` file).
4. Optional: your own decrypted copy of Monster Hunter XX, to add its gear looks and rare materials. You can skip it.

Copy the files into the folder with a USB cable or a file manager, then tap Check again. Encrypted copies cannot be used:
dump your own game decrypted from your 3DS (for example with GodMode9). The app checks each file and says what is wrong.

**The button on the first screen** says what the app will do, and it does it all under one bar:

- **Install:** downloads the mod's files, makes the mod from your dumps and builds the game's code.
- **Update to a version:** the same for a newer app, a newer mod or both. The app asks to be allowed to install itself the
  first time, and shows how to do it. When the app itself is new, everything else is done first; then the app closes by
  itself to install its new version (never with the game open), whatever is left goes on in the notifications, and a
  last notification says how it ended (tap it to open the app).
- **Build:** builds only the game's code again (it is missing, or it was made for another mod). More has Build again and
  Reinstall too.
- **Reinstall:** the mod's files on the phone were changed or are gone: the mod is made again before anything is built
  or played.
- **Resume:** goes on where a job stopped. What was done is not done again. Play stays beside it while the installed
  game can start, and Discard drops what the job left (the installed game stays).
- **Play:** starts the game.

**How long it takes.** Downloading the mod takes minutes. Building the game's code is the long part: it can take many
minutes and, on a modest phone, hours. Keep the phone plugged in and leave the app in the background if you like: the
job goes on, with a notification (Android 13 and later ask first whether to show it; the job runs the same without it),
and the first screen shows the time left. If the phone restarts or the job is cancelled, Resume goes on from where it
stopped. A later build for a new mod version reuses the parts that did not change, so it takes minutes. Building needs
free space and memory: the app says so before it starts if the phone has too little.

**Android 9 and 10.** On these versions the app keeps everything of its own, your save included, where no other app can
read it. To copy your save out or back, use Settings, System, Your game: Export save or Save folder.

The game's code stays on your phone: never share it.

### Name, id and releases

- **Name and id:** "Heavenfall", application id `io.github.popzol.heavenfall`. It installs beside 4u-native as an app of
  its own: they don't share a save, a setting or an update.
- **Test builds:** the debug build is "Heavenfall test", application id `io.github.popzol.heavenfall.test`, so a test
  build never replaces your install.
- **Releases:** `Heavenfall-android.apk` and its checksum `Heavenfall-android.apk.sha256`, in this repository's
  [Releases](https://github.com/popzol/heavenfall-recomp-android/releases). Every release is signed with the same key.
  To check the file on a PC: `sha256sum -c Heavenfall-android.apk.sha256`.
- **Version:** `heavenfall/VERSION` is the app's version (`versionName`; `versionCode` grows with it). The file `VERSION`
  is 4u-native's, names the engine, and stays as it is.
- **Everything else in the app, how to set up the game and its settings,** is as in 4u-native's own guide, which says it
  for the original: [README](https://github.com/khorne-hub/4u-native/blob/main/README.md).

## What is changed from 4u-native

- The name, the application id, the icon and the releases (above), and the key the releases are signed with.
- The app opens on its own first screen and first-run guide (above), which install the mod Heavenfall from its
  releases, keep it and the app up to date, and start the game.
- The engine carries Heavenfall's needs: the mod's own code recompiled with the game's, and its interface hooks.
- Building on the phone tries a stopped build once more and writes where it stopped in its log.
- The app's credits say it is a modified version, and where the original is.
- 4u-native's check of its own releases, and its warning about the APK's signing certificate, are taken out.
- The log's share authority follows the application id.

The changes are in this repository's history, and the original's own history is in it too.

## Building the app

On Windows (Git Bash), Linux or macOS, with the Android SDK (platform 35) and NDK 27.0.12077973, CMake 3.25 or newer,
Ninja, Python 3.10 or newer and a JDK 17:

```
sh scripts/build_sdl3.sh    # SDL 3.4.18 from its release, once
sh scripts/build_apk.sh     # libmain.so, the hook libraries, the on-phone builder's launchers and tools, then Gradle
```

Gradle builds the app's own native library (`libheavenfall.so`) with the SDK's CMake 3.22.1: install it with the SDK
manager (`sdkmanager "cmake;3.22.1"`), or name another CMake with `cmake.dir` in `android/local.properties`.

`scripts/android_env.sh` says where each tool is looked for. Without `android/keystore.properties` and
`android/mh4u.keystore` you get the debug APK, `android/app/build/outputs/apk/debug/app-debug.apk`. The unit tests:
`cd android && sh ./gradlew testDebugUnitTest`.

A release is made by GitHub Actions (`.github/workflows/build.yml`) in two jobs:

- **build** (every push, pull request and tag) builds the debug APK and runs the tests; on a tag `v<version>` (the
  version in `heavenfall/VERSION`) it also builds the release APK, unsigned. It never sees the signing key.
- **release** (tags only) runs in the GitHub environment `release`, and only for a tag whose commit is on `main`. It
  signs that APK with the environment's secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
  `KEY_PASSWORD`, checks it (16 KB alignment, the signature, never the debug key) and creates the release with
  `Heavenfall-android.apk`, `Heavenfall-android.apk.sha256` and `Heavenfall-android-release.json` (the release's files
  in the shape of GitHub's own answer, which the app reads when GitHub's API is out of reach). Without the four secrets
  it fails: it never signs with the debug key.

Once, before the first release, the repository's owner sets the environment up: Settings, Environments, New
environment `release`; under Deployment protection rules, Required reviewers (the people who approve each release) and,
under Deployment branches and tags, the tags `v*` only; then the four secrets as the environment's secrets (and
deleted from the repository's secrets). Each release then waits for a reviewer's approval before it is signed.

The actions are pinned to their commits, the CI's CMake and Ninja to their wheels' hashes
(`.github/ci-requirements.txt`), and the Gradle wrapper to its distribution's checksum (`distributionSha256Sum`); the
wrapper's jar is checked against Gradle's own.

`app_native/` is the app (SDL3 and Dear ImGui), `android/` its Android side, `heavenfall/` what is this version's own
(its version and its icon: `python heavenfall/tools/make_icons.py` makes the launcher icons), and `mh4n/` the runtime and
the recompiler (see [mh4n/README.md](mh4n/README.md)).

## Legal

This project is not affiliated with, endorsed by or sponsored by the Great C or the Great N. Monster Hunter is a trademark of
the Great C. The 3DS is a trademark of the Great N.

This repository and its releases contain no game code, game data, ROMs or keys. Use it only with a copy of the game you own.
Requests for game files, keys or ready-made `libmh4u_code_native.so` files will be closed.

Rights holders: please open an issue to get in touch.

## Credits

- The game's recompiler and the native runtime come from [MH4UltimateDC](https://github.com/CompaLarios18/MH4UltimateDC)
  by CompaLarios18 and its contributors.
- Parts of the runtime are derived from [Azahar](https://github.com/azahar-emu/azahar) and Citra.
- From the same author: [MHGU for Android and PC](https://github.com/khorne-hub/gu-native), Monster Hunter Generations
  Ultimate recompiled for Android and Windows the same way. Parts of the PC version (its stats readout, its zip reader and
  Discord Rich Presence) follow MHGU for PC's.
- The single-screen mod is [jM5557's](https://github.com/jM5557/mh4u-single-screen-mod), and is not part of this project.
- Contributed to development:
  - tabarong
  - Klark231
  - Alfonso Matador
  - popzol
  - BlitzWOLF
  - jM5557
- Single-screen mode places the menus where [Heavenfall](https://github.com/popzol/4u-heavenfall-mod-beta)'s
  single-screen mode puts them, by popzol and BlitzWOLF (GPL-3.0), without Heavenfall's changes to the game.
- The app's builder, which makes the game's code on the phone, downloads Python ([python.org](https://www.python.org), PSF
  License) and [Zig](https://ziglang.org) (MIT; with LLVM, Apache 2.0 with LLVM exceptions) the first time it runs; their
  licenses are in the app's **Licenses** too.
- The libraries this project uses, with their licenses, are listed in [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES). Their
  licenses are also in the app: gear, **Credits & Licenses**, **Licenses**.

## License

MH4U for Android and PC is free software under the [GNU General Public License, version 3](LICENSE) or (at your option) any later
version, like the MH4UltimateDC runtime it builds on. The third-party parts keep their own licenses.

The code by khorne-hub carries [additional terms](ADDITIONAL-TERMS), which section 7 of the GPL allows. Whoever shares the
app, changed or not, must:

- keep the author's credits: the ones the app shows when it opens, its **Credits & Licenses** page, and this file's
  **Credits** and **License** sections;
- mark a changed version as changed, and not present it as the official one;
- give, with the app, its license and its complete source code, as the GPL requires.
