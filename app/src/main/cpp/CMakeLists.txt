# Builds the AlphaPulse bridge to the Cactus Needle 3 engine (§30).
#
# Why the engine is a static library
# ----------------------------------
# Cactus publishes one engine per platform folder. For Android that is
# `android-arm64/libneedle.a` and `android-armv7/libneedle.a` - a plain `ar`
# archive plus `needle.h`, both vendored under `engine/` and verified by
# SHA-256 in `scripts/check_integrity.py` and `VendoredEngineArtifactBddSpec`.
# Nothing is compiled from Cactus source and nothing is downloaded at build
# time, so a build is reproducible and an offline machine can still build.
#
# Why the ABI mapping is explicit
# -------------------------------
# Cactus builds for arm64, armv7 and riscv64, but not x86_64. The app ships an
# x86_64 APK for emulators, so that ABI compiles the bridge with
# ALPHAPULSE_NEEDLE_ENGINE_BUNDLED=0: the bridge still loads, still answers, and
# reports "no engine for this ABI" instead of crashing in UnsatisfiedLinkError.
# The refusal is a real code path, not a stub - see Needle3EngineStatus.

cmake_minimum_required(VERSION 3.22.1)

project(alphapulse_needle CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pick the engine folder for the ABI being built, or leave it unset.
set(NEEDLE_ENGINE_DIR "")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(NEEDLE_ENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/engine/android-arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(NEEDLE_ENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/engine/android-armv7")
endif()

add_library(alphapulse_needle SHARED needle_jni.cpp)

# `needle.h` is comment-documented and identical for both ABIs.
target_include_directories(alphapulse_needle PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/engine")

if(NOT NEEDLE_ENGINE_DIR STREQUAL "")
    message(STATUS "Needle 3 engine: linking ${NEEDLE_ENGINE_DIR} for ${ANDROID_ABI}")

    add_library(needle3_engine STATIC IMPORTED)
    set_target_properties(needle3_engine PROPERTIES
        IMPORTED_LOCATION "${NEEDLE_ENGINE_DIR}/libneedle.a")

    target_compile_definitions(alphapulse_needle PRIVATE ALPHAPULSE_NEEDLE_ENGINE_BUNDLED=1)
    target_link_libraries(alphapulse_needle needle3_engine)
else()
    # The publisher ships no engine for this ABI (x86_64 emulators).
    message(STATUS "Needle 3 engine: none published for ${ANDROID_ABI}; building the refusal path")

    target_compile_definitions(alphapulse_needle PRIVATE ALPHAPULSE_NEEDLE_ENGINE_BUNDLED=0)
endif()

# Android logging, so a load or inference failure can be seen in logcat.
find_library(log_lib log)
target_link_libraries(alphapulse_needle ${log_lib})
