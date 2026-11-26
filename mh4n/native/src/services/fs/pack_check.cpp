// A code mod's packs against the code library, before the game runs.
//
// A pack is a file of the mod's RomFS that the mod's own code reads into memory at run time and runs there
// (Heavenfall's romfs/packs/*.bin, named in its recomp.json). The code library recompiled each from the bytes it had
// when the library was made, and says which (tools/game_version.py's rc_pack_count, rc_pack_files, rc_pack_size,
// rc_pack_crc32): the files the game will read now, its RomFS with the mods that are on laid over it, must be those,
// or the recompiled code would run with other data than its own. A library refuses to start then, as for the mod's
// code past the game's data (Runtime::CheckLibraryBuild). A library without packs (and one made before them) has
// nothing to check.
#include <cstddef>
#include <string>
#include <vector>

#include <fmt/format.h>

#include "fs_state.h"
#include "game_romfs.h"
#include "mh4n/cpu.h"
#include "mh4n/crc32.h"
#include "mh4n/log.h"
#include "mh4n/registry.h"
#include "mh4n/runtime.h"
#include "mh4n/service.h"

namespace mh4n::fs {

namespace {

constexpr const char* kLog = "Service.FS";
constexpr u32 kMaxPacks = 256;  // (a sane bound on what a library says)

// What one pack is now, or why it is not the one the library was made with ("" when it is).
std::string PackDifference(const std::string& path, u32 size, u32 crc32) {
    GameRomFsFile file;
    std::string why;
    if (!OpenGameRomFsFile(path, &file, &why)) return "not there (" + why + ")";
    if (file.size != size) return fmt::format("0x{:X} bytes, 0x{:X} when the library was made", file.size, size);
    std::vector<u8> data(static_cast<std::size_t>(file.size));
    if (file.Read(0, data.data(), data.size()) != data.size()) return "not readable";
    const u32 now = Crc32(data.data(), data.size());
    if (now != crc32) return fmt::format("crc32 {:08X}, {:08X} when the library was made", now, crc32);
    return {};
}

// Phase `started`: the services are up (the game's RomFS can be read as the game will), the game has not run.
void CheckPacks(Runtime& runtime) {
    const CodeLibrary* library = runtime.GetCodeHost().Library();
    if (library == nullptr) return;
    const auto* count = static_cast<const u32*>(library->Symbol("rc_pack_count"));
    const auto* files = static_cast<const char*>(library->Symbol("rc_pack_files"));
    const auto* sizes = static_cast<const u32*>(library->Symbol("rc_pack_size"));
    const auto* crcs = static_cast<const u32*>(library->Symbol("rc_pack_crc32"));
    if (count == nullptr || files == nullptr || sizes == nullptr || crcs == nullptr || *count == 0) return;
    if (*count > kMaxPacks) {
        MH4N_LOG(Error, kLog, "the code library {} names {} packs: it is no library these tools made", library->Path(),
                 *count);
        runtime.RequestExit(2, "a code library with a bad pack table");
        return;
    }
    if (ServiceManager* manager = FindServiceManager(runtime)) {
        GetFsState(manager->Context());  // (its archives, the game's RomFS among them, if no service made them yet)
    }
    // (rc_pack_files: the packs' paths in the RomFS, one per line)
    std::vector<std::string> paths;
    for (const char *at = files, *end = files; paths.size() < *count; at = end + 1) {
        for (end = at; *end != '\0' && *end != '\n'; ++end) {
        }
        paths.emplace_back(at, end);
        if (*end == '\0') break;
    }
    if (paths.size() != *count) {
        MH4N_LOG(Error, kLog, "the code library {} names {} packs but {} files: it is no library these tools made",
                 library->Path(), *count, paths.size());
        runtime.RequestExit(2, "a code library with a bad pack table");
        return;
    }
    for (u32 i = 0; i < *count; ++i) {
        const std::string difference = PackDifference(paths[i], sizes[i], crcs[i]);
        if (difference.empty()) continue;
        MH4N_LOG(Error, kLog,
                 "the code library {} was made with another build of the code mod: its pack rom:/{} is {}: make the "
                 "library again with the mods that are on",
                 library->Path(), paths[i], difference);
        runtime.RequestExit(2, "the code mod's packs are not the code library's");
        return;
    }
    MH4N_LOG(Info, kLog, "the code mod's {} packs are the ones the code library was made with", *count);
}

MH4N_REGISTER(BootStep, "started.packs", BootStep{Phase::Started, CheckPacks});

}  // namespace

}  // namespace mh4n::fs
