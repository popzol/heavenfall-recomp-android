# libheavenfall.so: the patch set's applier (../c, exported from Heavenfall's repository) with this folder's layer and its
# JNI, built by Gradle's externalNativeBuild for arm64-v8a. The shared C is built as the host tests build it (C99,
# -Wall -Wextra); the layer and the JNI add -Werror.
cmake_minimum_required(VERSION 3.22.1)
project(heavenfall C)

set(HF_SHARED "${CMAKE_CURRENT_LIST_DIR}/../c")

set(HF_APPLIER
    ${HF_SHARED}/sha256.c
    ${HF_SHARED}/romfs.c
    ${HF_SHARED}/ncch.c
    ${HF_SHARED}/dump.c
    ${HF_SHARED}/blz.c
    ${HF_SHARED}/patchset.c)

# The vendored deflate (miniz) is compiled with its own warnings off, as the tests compile it.
set_source_files_properties(${HF_SHARED}/zlib_mini.c PROPERTIES COMPILE_FLAGS "-w")
set_source_files_properties(${HF_APPLIER} PROPERTIES COMPILE_FLAGS "-std=c99 -Wall -Wextra")
set_source_files_properties(hf_core.c hf_jni.c PROPERTIES COMPILE_FLAGS "-std=gnu99 -Wall -Wextra -Werror")

add_library(heavenfall SHARED hf_core.c hf_jni.c ${HF_APPLIER} ${HF_SHARED}/zlib_mini.c)
target_include_directories(heavenfall PRIVATE ${HF_SHARED} ${CMAKE_CURRENT_LIST_DIR})
target_compile_options(heavenfall PRIVATE -O2 -fvisibility=hidden)

# 16 KB pages: every load segment aligned to 16384 (Android 15's devices with a 16 KB page size load only such libraries)
target_link_options(heavenfall PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
