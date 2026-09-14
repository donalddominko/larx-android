# Echo on-device transcription — native build (Phase 4).
#
# Produces a SINGLE shared library, libwhisper_jni.so, for arm64-v8a only.
# whisper.cpp is linked in STATICALLY: its own CMake target is named `whisper`,
# so we cannot also name our .so `whisper` (name collision). Hence one combined
# library `whisper_jni`, which Kotlin loads via System.loadLibrary("whisper_jni").
#
# AUTHORED-BUT-UNCOMPILED: written against whisper.cpp's CMake but not yet built
# (no NDK on the authoring machine). The dev-machine build materialises the
# submodule and assembles this. whisper.cpp's CMake option names drift between
# releases — verify the WHISPER_*/GGML_* flags below against the pinned tag.

cmake_minimum_required(VERSION 3.22.1)
project(echo_whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- whisper.cpp (pinned git submodule at ../cpp/whisper.cpp) ---
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "whisper.cpp submodule not initialised at ${WHISPER_DIR}. "
        "Run: git submodule update --init --recursive  (see PROGRESS.md Phase 4).")
endif()

# Build whisper.cpp as a CPU-only static lib with no extras; we link it in.
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
# Stock NDK sysroot has no libomp; keep OpenMP off (GGML uses its own threadpool).
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL)

# --- our JNI shim ---
add_library(whisper_jni SHARED whisper_jni.cpp)

find_library(log-lib log)
target_link_libraries(whisper_jni PRIVATE whisper ${log-lib})

# 16 KB page alignment (Play requirement). NDK r27 already links arm64 .so at
# max-page-size=16384 by default; set it explicitly so the guarantee survives a
# future NDK change and is auditable in-tree. Applies ONLY to our shared
# whisper_jni.so; the statically-linked whisper.cpp objects are unaffected.
target_link_options(whisper_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
