cmake_minimum_required(VERSION 3.22.1)

project("resonix_audio")

set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg")

if(NOT EXISTS "${FFMPEG_DIR}/lib/${ANDROID_ABI}/libavcodec.so")
    message(FATAL_ERROR
        "FFmpeg libraries not found at ${FFMPEG_DIR}/lib/${ANDROID_ABI}/. "
        "Run the 'Build FFmpeg (one-time, manual)' GitHub Actions workflow, "
        "download its resonix-ffmpeg-libs artifact, and commit its contents "
        "into app/src/main/cpp/ffmpeg/ before building.")
endif()

# Oboe is resolved as a Prefab package from the com.google.oboe:oboe
# Gradle dependency declared in app/build.gradle.kts (buildFeatures.prefab
# = true makes this available). No Oboe source is vendored in this repo.
find_package(oboe REQUIRED CONFIG)

add_library(resonix_audio SHARED
    native-lib.cpp
    AudioEngine.cpp
    AudioDecoder.cpp
)

target_compile_features(resonix_audio PRIVATE cxx_std_17)

target_include_directories(resonix_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${FFMPEG_DIR}/include
)

# FFmpeg ships as prebuilt .so files (see .github/workflows/build-ffmpeg.yml)
# rather than being built from source here — declared as imported targets.
add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES
    IMPORTED_LOCATION "${FFMPEG_DIR}/lib/${ANDROID_ABI}/libavutil.so")

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES
    IMPORTED_LOCATION "${FFMPEG_DIR}/lib/${ANDROID_ABI}/libswresample.so")

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES
    IMPORTED_LOCATION "${FFMPEG_DIR}/lib/${ANDROID_ABI}/libavcodec.so")

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES
    IMPORTED_LOCATION "${FFMPEG_DIR}/lib/${ANDROID_ABI}/libavformat.so")

target_link_libraries(resonix_audio
    android
    log
    oboe::oboe
    avformat
    avcodec
    swresample
    avutil
)
