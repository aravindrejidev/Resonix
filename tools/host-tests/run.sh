#!/usr/bin/env bash
# Builds the real AudioEngine + AudioDecoder + DSP on a Linux host against a stand-in for Oboe and runs
# the tests (gapless hand-over, ReplayGain on the boundary, seeks, platform resampling, ...).
#
#   Ubuntu/Debian:  sudo apt install g++ pkg-config ffmpeg libavcodec-dev libavformat-dev libswresample-dev libavutil-dev
#   Run:            tools/host-tests/run.sh            # engine tests
#                   SAN=thread tools/host-tests/run.sh # under ThreadSanitizer (or SAN=address,undefined)
#                   RING=1 tools/host-tests/run.sh     # also the 2^32-sample ring buffer test (about a minute)
set -euo pipefail
cd "$(dirname "$0")"
CPP=../../app/src/main/cpp
export RESONIX_TEST_MEDIA="${RESONIX_TEST_MEDIA:-$PWD/media}"
./make-media.sh "$RESONIX_TEST_MEDIA"

SAN_FLAGS=()
[ -n "${SAN:-}" ] && SAN_FLAGS=(-fsanitize="$SAN")
FF=$(pkg-config --cflags --libs libavformat libavcodec libswresample libavutil)

g++ -std=c++17 -O1 -g -Wall -Wextra "${SAN_FLAGS[@]}" -Istubs -Ifake -I"$CPP" \
    test_engine.cpp "$CPP/AudioEngine.cpp" "$CPP/AudioDecoder.cpp" -o test_engine $FF -lpthread
./test_engine

if [ -n "${RING:-}" ]; then
    g++ -std=c++17 -O2 -I"$CPP" test_ring.cpp -o test_ring
    ./test_ring
fi
