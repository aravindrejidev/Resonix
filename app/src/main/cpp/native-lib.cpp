#include <jni.h>

#include "AudioEngine.h"

// Function names are mangled from the fully-qualified Kotlin class:
//   com.resonix.player.audio.NativeAudioEngine
//        -> Java_com_resonix_player_audio_NativeAudioEngine_<method>
// Rename these to match if the package or class name ever changes.

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeCreate(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    auto *engine = new resonix::AudioEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeDestroy(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    delete reinterpret_cast<resonix::AudioEngine *>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativePlayTrack(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring filePath) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine == nullptr || filePath == nullptr) {
        return JNI_FALSE;
    }
    const char *pathChars = env->GetStringUTFChars(filePath, nullptr);
    bool ok = engine->playTrack(std::string(pathChars));
    env->ReleaseStringUTFChars(filePath, pathChars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativePauseTrack(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->pauseTrack();
    }
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeResumeTrack(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->resumeTrack();
    }
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSeekTo(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jlong positionMs) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->seekTo(positionMs);
    }
}

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetCurrentPositionMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getCurrentPositionMs() : 0L;
}

JNIEXPORT jboolean JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeIsPlaying(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return (engine != nullptr && engine->isPlaying()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeConsumeTrackFinished(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return (engine != nullptr && engine->consumeTrackFinishedEvent()) ? JNI_TRUE : JNI_FALSE;
}

// --- Track info getters -----------------------------------------------
// Kept as individual primitive-returning functions and assembled into
// an AudioTrackInfo on the Kotlin side (NativeAudioEngine.kt), rather
// than constructing a Kotlin object from JNI.

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetSampleRate(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().sampleRateHz : 0;
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetBitDepth(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().bitDepth : 0;
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetChannelCount(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().channelCount : 0;
}

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetBitrateBps(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().bitrateBps : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetDurationMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().durationMs : 0L;
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetFormatName(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    std::string name = engine != nullptr ? engine->getAudioTrackInfo().formatName : "";
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetTitle(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    std::string value = engine != nullptr ? engine->getAudioTrackInfo().title : "";
    return env->NewStringUTF(value.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetArtist(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    std::string value = engine != nullptr ? engine->getAudioTrackInfo().artist : "";
    return env->NewStringUTF(value.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetAlbum(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    std::string value = engine != nullptr ? engine->getAudioTrackInfo().album : "";
    return env->NewStringUTF(value.c_str());
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetYear(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().year : 0;
}

// --- Direct Volume Control ---------------------------------------------

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetGain(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jdouble linearGain) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->setGain(linearGain);
    }
}

JNIEXPORT jdouble JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetGain(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getGain() : 1.0;
}

}  // extern "C"
