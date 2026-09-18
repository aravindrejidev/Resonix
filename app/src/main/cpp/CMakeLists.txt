cmake_minimum_required(VERSION 3.22.1)

project("resonix_audio")

# Oboe is resolved as a Prefab package from the com.google.oboe:oboe
# Gradle dependency declared in app/build.gradle.kts (buildFeatures.prefab
# = true makes this available). No Oboe source is vendored in this repo.
find_package(oboe REQUIRED CONFIG)

add_library(resonix_audio SHARED
    native-lib.cpp
    AudioEngine.cpp
)

target_compile_features(resonix_audio PRIVATE cxx_std_17)

target_include_directories(resonix_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(resonix_audio
    android
    log
    oboe::oboe
)
