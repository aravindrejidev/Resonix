#include <jni.h>

#include "AudioEngine.h"

// Every function name below is mangled from the fully-qualified Kotlin
// class it binds to. If the package or class name changes, these must
// be renamed to match exactly or JNI resolution fails at load time:
//   com.resonix.player.audio.NativeAudioEngine
//        -> Java_com_resonix_player_audio_NativeAudioEngine_<method>

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

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativePlay(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->play();
    }
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativePause(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->pause();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeIsPlaying(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return (engine != nullptr) && engine->isPlaying();
}

}  // extern "C"
