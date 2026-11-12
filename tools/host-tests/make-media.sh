#!/usr/bin/env bash
# Generates the small test files (sines, a 96 kHz / 24-bit file, a mono file, ReplayGain-tagged FLACs)
# and their decoded float32 references with the ffmpeg command line.
set -euo pipefail
OUT="${1:-media}"
mkdir -p "$OUT"
cd "$OUT"
g() { local f=$1 d=$2 dur=$3 ch=$4; shift 4; ffmpeg -v error -y -f lavfi -i "sine=frequency=$f:sample_rate=$d:duration=$dur" -af "volume=0.5" -ac "$ch" "$@"; }
g 440 44100 2.0 2 -c:a pcm_s16le a_44k.wav
g 880 44100 2.0 2 -c:a pcm_s16le b_44k.wav
g 660 48000 1.0 2 -c:a pcm_s16le c_48k.wav
g 330 96000 2.0 2 -c:a pcm_s24le hi_96k.wav
g 550 44100 1.0 1 -c:a pcm_s16le mono_44k.wav
g 440 44100 2.0 2 -c:a flac -metadata REPLAYGAIN_TRACK_GAIN="-6.00 dB" -metadata REPLAYGAIN_TRACK_PEAK=0.5 a_rg.flac
g 880 44100 2.0 2 -c:a flac -metadata REPLAYGAIN_TRACK_GAIN="-12.00 dB" -metadata REPLAYGAIN_TRACK_PEAK=0.5 b_rg.flac
for f in a_44k b_44k c_48k hi_96k mono_44k; do ffmpeg -v error -y -i "$f.wav" -f f32le -acodec pcm_f32le "$f.raw"; done
for f in a_rg b_rg; do ffmpeg -v error -y -i "$f.flac" -f f32le -acodec pcm_f32le "$f.raw"; done
