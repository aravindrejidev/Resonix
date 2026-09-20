#include <jni.h>

#include <string>

#include "AudioDecoder.h"

// Bound to com.resonix.player.audio.TrackProber — a small, independent
// native object from AudioEngine/NativeAudioEngine, so a library scan
// can run at the same time as playback without two threads touching
// the same AudioDecoder (see AudioDecoder.h's threading contract).

namespace {
struct ProbeHandle {
    resonix::AudioDecoder decoder;
    resonix::TrackInfo lastInfo;
};
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_TrackProber_nativeCreate(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new ProbeHandle());
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_TrackProber_nativeDestroy(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    delete reinterpret_cast<ProbeHandle *>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_resonix_player_audio_TrackProber_nativeProbe(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring filePath) {
    auto *h = reinterpret_cast<ProbeHandle *>(handle);
    if (h == nullptr || filePath == nullptr) {
        return JNI_FALSE;
    }
    const char *pathChars = env->GetStringUTFChars(filePath, nullptr);
    bool ok = h->decoder.open(std::string(pathChars), &h->lastInfo);
    env->ReleaseStringUTFChars(filePath, pathChars);
    h->decoder.close();  // metadata only — never kept open for playback
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetSampleRate(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ProbeHandle *>(handle)->lastInfo.sampleRateHz;
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetBitDepth(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ProbeHandle *>(handle)->lastInfo.bitDepth;
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetChannelCount(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ProbeHandle *>(handle)->lastInfo.channelCount;
}

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetBitrateBps(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ProbeHandle *>(handle)->lastInfo.bitrateBps;
}

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetDurationMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ProbeHandle *>(handle)->lastInfo.durationMs;
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetFormatName(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *h = reinterpret_cast<ProbeHandle *>(handle);
    return env->NewStringUTF(h->lastInfo.formatName.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetTitle(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *h = reinterpret_cast<ProbeHandle *>(handle);
    return env->NewStringUTF(h->lastInfo.title.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetArtist(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *h = reinterpret_cast<ProbeHandle *>(handle);
    return env->NewStringUTF(h->lastInfo.artist.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetAlbum(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *h = reinterpret_cast<ProbeHandle *>(handle);
    return env->NewStringUTF(h->lastInfo.album.c_str());
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_TrackProber_nativeGetYear(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ProbeHandle *>(handle)->lastInfo.year;
}

}  // extern "C"
